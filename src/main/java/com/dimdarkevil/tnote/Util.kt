package com.dimdarkevil.tnote

import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import java.io.File
import java.io.FileInputStream
import java.sql.Connection
import java.sql.ResultSet
import java.time.LocalDate
import java.util.*

const val ANSI_RESET = "\u001B[0m"

const val ANSI_BOLD = "\u001B[1m"
const val ANSI_DIM = "\u001B[2m"
const val ANSI_UNDERLINE = "\u001B[4m"
const val ANSI_BLINK = "\u001B[5m"
const val ANSI_REVERSE = "\u001B[7m"
const val ANSI_INVISIBLE = "\u001B[8m"

const val ANSI_BLACK = "\u001B[30m"
const val ANSI_RED = "\u001B[31m"
const val ANSI_GREEN = "\u001B[32m"
const val ANSI_YELLOW = "\u001B[33m"
const val ANSI_BLUE = "\u001B[34m"
const val ANSI_PURPLE = "\u001B[35m"
const val ANSI_CYAN = "\u001B[36m"
const val ANSI_WHITE = "\u001B[37m"
const val ANSI_BLACK_BG = "\u001B[40m"
const val ANSI_RED_BG = "\u001B[41m"
const val ANSI_GREEN_BG = "\u001B[42m"
const val ANSI_YELLOW_BG = "\u001B[43m"
const val ANSI_BLUE_BG = "\u001B[44m"
const val ANSI_PURPLE_BG = "\u001B[45m"
const val ANSI_CYAN_BG = "\u001B[46m"
const val ANSI_WHITE_BG = "\u001B[47m"

val HOME = System.getProperty("user.home")
val SPACE_RE = Regex("\\s+")

data class AppConfig(
	val storage: String,
	val editor: String,
	val viewer: String,
	val browser: String,
	val stylesheet: String
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

data class Todo(
	val id: Int,
	val txt: String,
	val priority: Int,
	val complete: Boolean,
	val created: String,
	val completed: String
)

enum class Kind {
	ENTRY,
	LINK,
	DOC,
	IMAGE
}

enum class Command {
	ADD,
	BLANK,
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

enum class TodoCommand {
	ADD,
	LIST,
	COMPLETE,
	REMOVE,
	DETAILS,
	ALL
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
				stylesheet={optional full path to stylesheet for html rendering}
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
	val stylesheet = props.getProperty("stylesheet") ?: ""
	return AppConfig(storage, editor, viewer, browser, stylesheet)
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
			stmt.execute("CREATE TABLE todo (id INTEGER PRIMARY KEY, txt TEXT, priority INTEGER, complete BOOLEAN, created STRING, completed STRING);")
			stmt.execute("CREATE INDEX idx_todo_complete ON todo (complete);")
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

fun String.rpad(len: Int) : String {
	return if (this.length >= len) {
		this
	} else {
		this+" ".repeat(len-this.length)
	}
}

fun Int.lpad(len: Int) : String {
	var s = "$this"
	while (s.length < len) s = " $s"
	return s
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

fun loadTodoFromResultSet(rs: ResultSet) : Todo {
	return Todo(
		rs.getInt(1),
		rs.getString(2),
		rs.getInt(3),
		rs.getBoolean(4),
		rs.getString(5),
		rs.getString(6)
	)
}

fun noteToConsoleString(n: Note) : String {
	return when (n.kind) {
		Kind.ENTRY -> {
			"""
					${ANSI_RED}(${n.id})${ANSI_RESET} - ${ANSI_BOLD}${ANSI_BLUE}${n.dt}${ANSI_RESET} - ${ANSI_BOLD}${ANSI_YELLOW}${n.tags.joinToString(" ", "[", "]")}${ANSI_RESET}
					${n.txt}
				""".trimIndent()
		}
		Kind.IMAGE, Kind.LINK, Kind.DOC -> {
			"""
					${ANSI_RED}(${n.id})${ANSI_RESET} - ${ANSI_BOLD}${ANSI_BLUE}${n.dt}${ANSI_RESET} - ${ANSI_BOLD}${ANSI_YELLOW}${n.tags.joinToString(" ", "[", "]")}${ANSI_RESET}
					${ANSI_DIM}${n.kind} (${n.origFile})${ANSI_RESET}
					${n.file}
					${n.txt}
				""".trimIndent()
		}
	}
}

fun noteToHtmlString(n: Note, parser: Parser, renderer: HtmlRenderer) : String {
	return when (n.kind) {
		Kind.ENTRY -> {
			"""
				<h2>(${n.id}) - ${n.dt} - ${n.tags.joinToString(" ", "[", "]")}</h2>
				<p>${n.txt}</p>
			""".trimIndent()
		}
		Kind.LINK -> {
			val txt = if (n.txt.isEmpty()) n.file else n.txt
			"""
				<h2>(${n.id}) - ${n.dt} - ${n.tags.joinToString(" ", "[", "]")}</h2>
				<a target="_blank" href="${n.file}">$txt</a>				
			""".trimIndent()
		}
		Kind.IMAGE -> {
			val filename =File(n.file).name
			val txt = if (n.txt.isEmpty()) n.file else n.txt
			"""
				<h2>(${n.id}) - ${n.dt} - ${n.tags.joinToString(" ", "[", "]")}</h2>
				<img src="images/${filename}" alt="$txt">				
			""".trimIndent()
		}
		Kind.DOC -> {
			val doc = parser.parse(File(n.file).readText(Charsets.UTF_8))
			"""
				<h2>(${n.id}) - ${n.dt} - ${n.tags.joinToString(" ", "[", "]")}</h2>
				${renderer.render(doc)}
			""".trimIndent()
		}
	}
}
