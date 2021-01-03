package com.dimdarkevil.tnote

import java.io.File
import java.io.FileInputStream
import java.sql.Connection
import java.sql.ResultSet
import java.time.LocalDate
import java.util.*

val HOME = System.getProperty("user.home")
val SPACE_RE = Regex("\\s+")

data class AppConfig(
	val storage: String,
	val editor: String,
	val viewer: String,
	val browser: String
)


data class Note(
	val id: Int,
	val txt: String,
	val dt: LocalDate,
	val file: String,
	val origFile: String,
	val kind: Kind,
	val created: String,
	var tags: List<String> = emptyList()
)

enum class Kind {
	ENTRY,
	LINK,
	DOC,
	IMAGE
}

enum class Command {
	ADD,
	LINK,
	DOC,
	IMAGE,
	TAIL,
	REMOVE,
	SHOW,
	EDIT,
	TAGS,
	RETAG,
}

fun loadConfig() : AppConfig {
	val propFile = File(HOME, ".tnote")
	val msg = """
				add a prop file at ~/.tnote
				it should have:
				storage={full path to storage location}
				editor={text editor command}
				viewer={image viewer command}
				browser={web browser command}
			""".trimIndent()
	if (!propFile.exists()) {
		throw RuntimeException(msg)
	}
	val props = Properties()
	props.load(FileInputStream(propFile))
	val storage = props.getProperty("storage") ?: throw RuntimeException("-=-= 'storage' key does not exist in config\n$msg")
	val editor = props.getProperty("editor") ?: throw RuntimeException("-=-= 'editor' key does not exist in config\n$msg")
	val viewer = props.getProperty("viewer") ?: throw RuntimeException("-=-= 'viewer' key does not exist in config\n$msg")
	val browser = props.getProperty("browser") ?: throw RuntimeException("-=-= 'browser' key does not exist in config\n$msg")
	return AppConfig(storage, editor, viewer, browser)
}

fun prep(config: AppConfig) : Pair<File,DbConn> {
	val storageDir = File(config.storage.resolveTilde())
	if (!storageDir.exists()) storageDir.mkdirs()
	val dbFile = File(storageDir, "tnote.db")
	return Pair(storageDir, initDb(dbFile))
}

fun initDb(dbFile: File) : DbConn {
	if (!dbFile.exists()) createDb(dbFile)
	return DbConn(dbFile)
}

fun createDb(dbFile: File) {
	DbConn(dbFile).transact { con ->
		con.createStatement().use { stmt ->
			stmt.execute("CREATE TABLE notes (id INTEGER PRIMARY KEY, txt TEXT, dt TEXT, file TEXT, orig_file TEXT, kind TEXT, created TEXT);")
			stmt.execute("CREATE INDEX idx_notes_dt ON notes (dt);")
			stmt.execute("CREATE TABLE tags (id INTEGER PRIMARY KEY, note_id INTEGER, tag TEXT);")
			stmt.execute("CREATE INDEX idx_tags_tag ON tags (tag);")
		}
	}
}


fun String.resolveTilde() = if (this.startsWith("~")) {
	"${HOME}${this.substring(1)}"
} else {
	this
}

fun String.trimQuotes() = if (this.startsWith('"') && this.endsWith('"')) {
	this.substring(1, this.length-1)
} else {
	this
}

fun execBash(cmd: String, args: List<String> = listOf(), wait: Boolean = false) : Int	{
	val dir = File(System.getProperty("user.dir"))
	val pb = if (args.isNotEmpty()) {
		println("running $cmd with args ${args} in dir ${dir}")
		ProcessBuilder(cmd, *args.toTypedArray()).directory(dir).inheritIO()
	} else {
		ProcessBuilder(cmd).directory(dir).inheritIO()
	}
	val proc = pb.start()
	return if (wait) {
		proc.waitFor()
	} else 0
}

fun loadNoteFromResultSet(rs: ResultSet) : Note {
	return Note(
		id = rs.getInt(1),
		txt = rs.getString(2),
		dt = LocalDate.parse(rs.getString(3)),
		file = rs.getString(4),
		origFile = rs.getString(5),
		kind = Kind.valueOf(rs.getString(6).toUpperCase()),
		created = rs.getString(7)
	)
}

fun loadTagsForNote(con: Connection, id: Int): List<String> {
	return con.prepareStatement("SELECT tag from tags WHERE note_id=?").use { stmt ->
		stmt.setInt(1, id)
		val tagList = mutableListOf<String>()
		stmt.executeQuery().use { rs ->
			while (rs.next()) {
				tagList.add(rs.getString(1))
			}
		}
		tagList
	}
}

fun printNote(n: Note) {
	val s = when (n.kind) {
		Kind.ENTRY -> {
			"""
					${n.id}
					${n.dt} - ${n.tags.joinToString(",", "[", "]")}
					${n.txt}
				""".trimIndent()
		}
		Kind.IMAGE, Kind.LINK, Kind.DOC -> {
			"""
					${n.id}
					${n.dt} - ${n.tags.joinToString(",", "[", "]")}
					${n.kind} (${n.origFile})
					${n.file}
					${n.txt}
				""".trimIndent()
		}
	}
	println(s)
}
