package com.dimdarkevil.tnote

import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.URL
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.*
import kotlin.Exception
import kotlin.math.absoluteValue
import kotlin.system.exitProcess


object NoteTaker {
	private val log = LoggerFactory.getLogger(NoteTaker::class.java)
	private val cmds = Command.values().map { it.name }.toSet()

	@JvmStatic
	fun main(args: Array<String>) {
		try {
			val config = loadConfig()
			if (args.isEmpty()) throw RuntimeException(help())
			if (args.size == 1 && (args[0] == "-h" || args[0] == "--help")) throw RuntimeException(help())
			// four ways this could be:
			// command tags content
			// command content
			// tags content
			// content
			// for the last two, the assumed command is "add"
			val firstarg = args[0].uppercase()
			val hasCommand = (cmds.contains(firstarg))
			val command = if (hasCommand) Command.valueOf(firstarg) else Command.ADD
			if (command == Command.LINK && args.size < 2) {
				throw RuntimeException("must supply a link")
			}
			if (command == Command.DOC && args.size < 2) {
				throw RuntimeException("must supply a filename")
			}
			val argstr = if (hasCommand) args.drop(1).joinToString(" ") else args.joinToString(" ")
			//println("command is $command, argstr is $argstr")
			val (storageDir, dbConn) = prep(config)
			when (command) {
				Command.ADD -> add(argstr, dbConn)
				Command.BLANK -> addBlankDoc(argstr, dbConn, storageDir, config)
				Command.LINK -> addLink(args[1], args.drop(2).joinToString(" "), dbConn)
				Command.DOC -> addDocOrImage("doc", args[1], args.drop(2).joinToString(" "), dbConn, storageDir)
				Command.IMAGE -> addDocOrImage("image", args[1], args.drop(2).joinToString(" "), dbConn, storageDir)
				Command.TAIL -> tail(argstr, dbConn)
				Command.REMOVE -> remove(argstr, dbConn)
				Command.SHOW -> show(argstr, dbConn, config, storageDir)
				Command.EDIT -> edit(argstr, dbConn)
				Command.TAGS -> listTags(dbConn)
				Command.RETAG -> retag(argstr, dbConn)
				Command.REFLOG -> reflog()
			}
		} catch (e: Exception) {
			println(e.message)
			//log.error(e.message, e)
			exitProcess(1)
		}
	}

	fun getTagsAndContent(argstr: String, defTag: String) : Pair<List<String>, String> {
		val closeBracketPos = argstr.indexOfFirst { it == ']' }
		val hasTags = argstr.startsWith("[") && closeBracketPos > 0
		val tags = if (hasTags) {
			argstr.substring(1, closeBracketPos).trim().split(SPACE_OR_COMMA_RE).map { it.trim().lowercase() }
		} else {
			listOf(defTag)
		}
		val content = if (hasTags) {
			argstr.substring(closeBracketPos+1).trim().trimQuotes()
		} else {
			argstr.trim().trimQuotes()
		}
		return Pair(tags, content)
	}

	fun add(argstr: String, dbConn: DbConn) {
		val (tags, content) = getTagsAndContent(argstr, "none")
		insertEntry(dbConn, "", "", tags, content, Kind.ENTRY)
	}

	fun addLink(link: String, argstr: String, dbConn: DbConn) {
		val (pretags, content) = getTagsAndContent(argstr, "link")
		val tags = if (pretags.contains("link")) {
			pretags
		} else {
			pretags.plus("link")
		}
		URL(link) // is this here so that we bomb if it's not a valid link or something?
		insertEntry(dbConn, link, "", tags, content, Kind.LINK)
	}

	fun addBlankDoc(argstr: String, dbConn: DbConn, storageDir: File, config: AppConfig) {
		val type = "doc"
		val (pretags, content) = getTagsAndContent(argstr, type)
		val tags = if (pretags.contains(type)) {
			pretags
		} else {
			pretags.plus(type)
		}
		val relativeFilename = "${type}s/${UUID.randomUUID()}.md"
		val destFile = File(storageDir, relativeFilename)
		destFile.parentFile.mkdirs()
		destFile.writeText("", Charsets.UTF_8)
		val note = insertEntry(dbConn, relativeFilename, destFile.name, tags, content, Kind.DOC)
		execBash(config.editor, listOf(note.file))
	}

	fun addDocOrImage(type: String, filename: String, argstr: String, dbConn: DbConn, storageDir: File) {
		val (pretags, content) = getTagsAndContent(argstr, type)
		val tags = if (pretags.contains(type)) {
			pretags
		} else {
			pretags.plus(type)
		}
		val f = File(filename.resolveTilde())
		if (!f.exists()) throw RuntimeException("File does not exist: ${f.path}")
		if (f.isDirectory) throw RuntimeException("File is a directory: ${f.path}")
		val relativeFilename = "${type}s/${UUID.randomUUID()}.${f.extension}"
		val destFile = File(storageDir, relativeFilename)
		destFile.parentFile.mkdirs()
		f.copyTo(destFile)
		if (!destFile.exists()) throw IOException("Error writing file from ${f.path}")
		insertEntry(dbConn, relativeFilename, f.name, tags, content, Kind.valueOf(type.uppercase()))
	}

	fun insertEntry(dbConn: DbConn, file: String, origFile: String, tags: List<String>, content: String, kind: Kind) : Note {
		val today = LocalDate.now()
		val now = LocalDateTime.now().toString()
		return dbConn.perform { con ->
			val stmt = con.prepareStatement("INSERT INTO notes (txt, dt, file, orig_file, kind, created) values(?,?,?,?,?,?);")
			stmt.setString(1, content)
			stmt.setString(2, "$today")
			stmt.setString(3, file)
			stmt.setString(4, origFile)
			stmt.setString(5, kind.name)
			stmt.setString(6, now)
			stmt.executeUpdate()
			val rs = stmt.generatedKeys
			rs.next()
			val id = rs.getInt(1)
			con.prepareStatement("INSERT INTO tags(note_id, tag) VALUES(?,?);").use { tstmt ->
				tags.forEach { tag ->
					tstmt.setInt(1, id)
					tstmt.setString(2, tag)
					tstmt.execute()
				}
			}
			Note(id, content, today, file, origFile, kind, now, tags)
		}
	}

	fun tail(argstr: String, dbConn: DbConn) {
		val num = try {
			argstr.trim().toInt()
		} catch (e: Exception) {
			10
		}
		val notes = dbConn.perform { con ->
			val nlist = mutableListOf<Note>()
			con.prepareStatement("SELECT id,txt,dt,file,orig_file,kind,created from notes order by id DESC LIMIT ${num};").use { stmt ->
				stmt.executeQuery().use { rs ->
					while (rs.next()) {
						val n = loadNoteFromResultSet(rs)
						nlist.add(n)
					}
				}
			}
			nlist.forEach { n -> n.tags = loadTagsForNote(con, n.id) }
			nlist.reversed()
		}
		notes.forEach {
			println()
			println(noteToConsoleString(it))
		}
		println()
	}

	fun remove(argstr: String, dbConn: DbConn) {
		val id = try {
			argstr.trim().toInt()
		} catch (e: Exception) {
			throw RuntimeException("$argstr is not an integer. usage: tn remove id")
		}
		dbConn.transact { con ->
			val filename = con.prepareStatement("SELECT file from notes where id=?;").use { stmt ->
				stmt.setInt(1, id)
				stmt.executeQuery().use { rs ->
					if (rs.next()) {
						rs.getString(1)
					} else {
						throw RuntimeException("No note with id ${id} found")
					}
				}
			}
			if (filename.isNotEmpty()) File(filename).delete()
			con.createStatement().use { stmt ->
				stmt.execute("DELETE FROM tags WHERE note_id=${id}")
				stmt.execute("DELETE FROM notes WHERE id=${id}")
			}
			println("removed $id")
		}
	}

	fun show(argstr: String, dbConn: DbConn, config: AppConfig, storageDir: File) {
		val id = try {
			argstr.trim().toInt()
		} catch (e: Exception) {
			throw RuntimeException("$argstr is not an integer. usage: tn show id")
		}
		val note = getById(id, dbConn)
		println(noteToConsoleString(note))
		when (note.kind) {
			Kind.ENTRY -> {}
			Kind.LINK -> execBash(config.browser, listOf(note.file))
			Kind.DOC -> execBash(config.editor, listOf(File(storageDir, note.file).path))
			Kind.IMAGE -> execBash(config.viewer, listOf(File(storageDir, note.file).path))
		}
	}

	fun edit(argstr: String, dbConn: DbConn) {
		val editor = if (System.getenv("EDITOR") != null) {
			System.getenv("EDITOR")
		} else {
			"/usr/bin/nano"
		}
		val id = try {
			argstr.trim().toInt()
		} catch (e: Exception) {
			throw RuntimeException("$argstr is not an integer. usage: tn show id")
		}
		val note = getById(id, dbConn)
		val tmpDir = File(System.getProperty("java.io.tmpdir"))

		val tmpFile = File(tmpDir, "${UUID.randomUUID()}.properties")
		val noteStr = """
			date=${note.dt}
			content=${note.txt}
			tags=${note.tags.joinToString(",", "[", "]")}
		""".trimIndent()
		tmpFile.writeText(noteStr, Charsets.UTF_8)
		println("running editor ${editor}")
		val exitCode = execBash(editor, listOf(tmpFile.path), true)
		println("done editing. exit code was $exitCode")
		if (exitCode == 0) {
			val props = Properties()
			props.load(FileInputStream(tmpFile))
			val dateStr = props["date"] as? String ?: throw RuntimeException("date missing from file")
			val contentStr = (props["content"] as? String)?.trim() ?: throw RuntimeException("content missing from file")
			val tagsStr = props["tags"] as? String ?: throw RuntimeException("tags missing from file")
			if (!tagsStr.startsWith("[") || !tagsStr.endsWith("]")) {
				throw RuntimeException("tags improperly formatted")
			}
			val dt = LocalDate.parse(dateStr)
			val tags = tagsStr.substring(1, tagsStr.length-1).split(SPACE_OR_COMMA_RE).map { it.trim().lowercase() }
			val tagsSame = (
				tags.size == note.tags.size &&
				tags.all { note.tags.contains(it) } &&
				note.tags.all { tags.contains(it) }
			)
			if (tagsSame && dt == note.dt && contentStr == note.txt) {
				println("nothing changed. not writing")
				return
			}
			dbConn.transact { con ->
				con.prepareStatement("UPDATE notes set txt=?, dt=? WHERE id=?;").use { stmt ->
					stmt.setString(1, contentStr)
					stmt.setString(2, "$dt")
					stmt.setInt(3, note.id)
					stmt.execute()
				}
				con.createStatement().use { stmt ->
					stmt.execute("DELETE from tags WHERE note_id=${note.id}")
				}
				con.prepareStatement("INSERT INTO tags(note_id, tag) VALUES(?,?);").use { tstmt ->
					tags.forEach { tag ->
						tstmt.setInt(1, note.id)
						tstmt.setString(2, tag)
						tstmt.execute()
					}
				}
			}
			println("updated note $id")
		}
	}

	fun listTags(dbConn: DbConn) {
		dbConn.perform { con ->
			con.createStatement().use { stmt ->
				stmt.executeQuery("SELECT DISTINCT tag FROM tags ORDER BY tag").use { rs ->
					while (rs.next()) {
						println(rs.getString(1))
					}
				}
			}
		}
	}

	fun retag(argstr: String, dbConn: DbConn) {
		val tagChanges = argstr.split(SPACE_RE).map { it.lowercase().trim() }
		if (tagChanges.size != 2) {
			throw RuntimeException("usage: nt retag oldtag newtag")
		}
		val oldtag = tagChanges[0]
		val newtag = tagChanges[1]
		dbConn.perform { con ->
			con.prepareStatement("UPDATE tags set tag=? WHERE tag=?").use { stmt ->
				stmt.setString(1, newtag)
				stmt.setString(2, oldtag)
				stmt.execute()
			}
		}
	}

	fun reflog() {
		val rsp = reflogResponses[Random(System.currentTimeMillis()).nextInt().absoluteValue % reflogResponses.size]
		println(rsp)
	}

	fun getById(id: Int, dbConn: DbConn) : Note {
		return dbConn.perform { con ->
			val note = con.prepareStatement("SELECT id,txt,dt,file,orig_file,kind,created from notes where id=?;").use { stmt ->
				stmt.setInt(1, id)
				stmt.executeQuery().use { rs ->
					if (rs.next()) {
						loadNoteFromResultSet(rs)
					} else {
						throw RuntimeException("Note with id $id not found")
					}
				}
			}
			note.tags = loadTagsForNote(con, note.id)
			note
		}
	}

	fun help() : String {
		return """
			tn version ${Version.version}
			usage:
			tn note
			tn [tags] note
			tn add note
			tn add [tags] note
			tn blank note
			tn blank [tags] note
			tn doc filename
			tn doc filename [tags] note
			tn link url
			tn link url [tags] note
			tn image filename
			tn image filename [tags] note
			tn tail {num recs}
			tn remove id
			tn show id
			tn edit id
			tn retag oldtag newtag
			tn reflog
		""".trimIndent()
	}


}
