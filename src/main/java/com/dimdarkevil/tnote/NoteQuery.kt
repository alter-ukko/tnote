package com.dimdarkevil.tnote

import org.docopt.Docopt
import java.time.LocalDate
import kotlin.system.exitProcess

class ExitProcessException : Exception()

object NoteQuery {
	private val help = """
		tnq version ${Version.version}
		Usage:
			tnq [--text=<text to search>] [--tags=<tag list>] [--start=<start date>] [--end=<end date>] [--kinds=<kind list>] [--out=<folder>]
			tnq --version
			tnq -h | --help
		Options:
			--text=<text to search>   Search for notes whose content contains the text.
			--tags=<tag list>         Comma-separated list of tags. Will return notes containing all
			--start=<start date>      ISO-8601 starting date
			--end=<end date>          ISO-8601 ending date
			--kinds=<kind list>       Comma-separeated list of kinds: ENTRY, DOC, IMAGE, LINK
			--out=<folder>            folder to output html (otherwise lists to console)
		""".trimIndent().replace("\t","    ")

	@JvmStatic
	fun main(args: Array<String>) {
		val opts = try {
			docOptFor(help, args)
		} catch (e: ExitProcessException) {
			exitProcess(0)
		}
		//println("opts is $opts")
		val config = loadConfig()
		val (storageDir, dbConn) = prep(config)
		try {
			val text = (opts["--text"] as? String)?.trim() ?: ""
			val tags = (opts["--tags"] as? String)?.let { tagStr ->
				tagStr.split(",").map { it.trim().toLowerCase() }.filter { it.isNotEmpty() }
			} ?: emptyList()
			val startDate = (opts["--start"] as? String)?.let { sdStr ->
				LocalDate.parse(sdStr).toString()
			} ?: ""
			val endDate = (opts["--end"] as? String)?.let { edStr ->
				LocalDate.parse(edStr).toString()
			} ?: ""
			val kinds = (opts["--kinds"] as? String)?.let { kindStr ->
				kindStr.split(",")
					.map { it.trim().toUpperCase() }
					.filter { it.isNotEmpty() }
					.map { Kind.valueOf(it) }
			} ?: emptyList()
			val outStr = (opts["--out"] as? String) ?: ""

			val clause = listOf(
				if (kinds.isNotEmpty()) {
					"kind in ${kinds.joinToString(",", "(", ")") { "'${it.name}'" }}"
				} else {
					""
				},
				if (startDate.isNotEmpty()) {
					"dt >= '${startDate}'"
				} else {
					""
				},
				if (endDate.isNotEmpty()) {
					"dt <= '${endDate}'"
				} else {
					""
				}
			).filter { it.isNotEmpty() }
				.joinToString(" AND ")
			val baseQry = "SELECT id,txt,dt,file,orig_file,kind,created FROM notes"
			val qry = if (clause.isNotEmpty()) {
				"$baseQry WHERE $clause;"
			} else {
				baseQry
			}
			/*
			println("qry is $qry")
			println("tags: $tags")
			println("text: $text")
			*/
			val notes = dbConn.perform { con ->
				val noteList = mutableListOf<Note>()
				con.createStatement().use { stmt ->
					stmt.executeQuery(qry).use { rs ->
						while (rs.next()) {
							noteList.add(loadNoteFromResultSet(rs))
						}
					}
				}
				noteList.filter { n ->
					n.tags = loadTagsForNote(con, n.id)
					tags.isEmpty() || tags.all { n.tags.contains(it) }
				}.filter { n ->
					text.isEmpty() || n.txt.contains(text, true)
				}
			}
			if (notes.isEmpty()) println("no records found")
			notes.forEach {
				println()
				printNote(it)
			}

		} catch (e: Exception) {
			println(e.message)
			exitProcess(1)
		}
	}

	fun docOptFor(help: String, args: Array<String>) : Map<String,Any> {
		val opts = Docopt(help)
			.withHelp(true)
			.withExit(true)
			.parse(args.toList())
		if (opts["--version"] == true) {
			println(help.split("\n").first())
			throw ExitProcessException()
		}
		return opts
	}
}