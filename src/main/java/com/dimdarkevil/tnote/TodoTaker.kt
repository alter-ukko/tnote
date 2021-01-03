package com.dimdarkevil.tnote

import java.sql.Connection
import java.time.LocalDateTime
import kotlin.system.exitProcess

object TodoTaker {

	@JvmStatic
	fun main(args: Array<String>) {
		try {
			if (args.isEmpty() || args[0] == "--help" || args[0] == "-h") {
				println(help())
				exitProcess(0)
			}
			val config = loadConfig()
			val (storageDir, dbConn) = prep(config)
			val command = try {
				TodoCommand.valueOf(args[0].toUpperCase())
			} catch (e: Exception) {
				throw RuntimeException(help())
			}
			val argstr = args.drop(1).joinToString(" ")
			when (command) {
				TodoCommand.ADD -> add(argstr, dbConn)
				TodoCommand.LIST -> list(dbConn)
				TodoCommand.COMPLETE -> complete(argstr, dbConn)
				TodoCommand.REMOVE -> remove(argstr, dbConn)
			}
		}	catch (e: Exception) {
			println(e.message)
			exitProcess(1)
		}
	}

	fun add(argstr: String, dbConn: DbConn) {
		dbConn.transact { con ->
			con.prepareStatement("INSERT INTO todo (txt,priority,complete,created,completed) VALUES(?,?,?,?,?);").use { stmt ->
				stmt.setString(1, argstr.trim())
				stmt.setInt(2, 1)
				stmt.setBoolean(3, false)
				stmt.setString(4, LocalDateTime.now().toString())
				stmt.setString(5, "")
				stmt.execute()
			}
		}
	}

	fun list(dbConn: DbConn) {
		val todos = getTodos(dbConn)
		dbConn.perform { con ->
			if (todos.isNotEmpty()) {
				todos.forEachIndexed { idx, todo ->
					println("${idx+1} - ${todo.txt}")
				}
			} else {
				println("no incomplete todos")
			}
		}
	}

	fun complete(argstr: String, dbConn: DbConn) {
		val idx = try {
			argstr.trim().toInt()-1
		} catch (e: Exception) {
			throw RuntimeException("$argstr is not an integer")
		}
		val todos = getTodos(dbConn)
		dbConn.perform { con ->
			if (idx < 0 || idx >= todos.size) throw RuntimeException("no item for index ${idx+1}")
			con.prepareStatement("UPDATE todo set complete=?, completed=? where id=?").use { stmt ->
				stmt.setBoolean(1, true)
				stmt.setString(2, LocalDateTime.now().toString())
				stmt.setInt(3, todos[idx].id)
				stmt.execute()
			}
			println("""completed "${todos[idx].txt}" """)
		}
	}

	fun remove(argstr: String, dbConn: DbConn) {
		val idx = try {
			argstr.trim().toInt()-1
		} catch (e: Exception) {
			throw RuntimeException("$argstr is not an integer")
		}
		val todos = getTodos(dbConn)
		dbConn.perform { con ->
			if (idx < 0 || idx >= todos.size) throw RuntimeException("no item for index ${idx+1}")
			con.prepareStatement("DELETE FROM todo where id=?").use { stmt ->
				stmt.setInt(1, todos[idx].id)
				stmt.execute()
			}
			println("""removed "${todos[idx].txt}" """)
		}
	}

	fun getTodos(dbConn: DbConn) : List<Todo> {
		return dbConn.perform { con ->
			con.createStatement().use { stmt ->
				val tdlist = mutableListOf<Todo>()
				stmt.executeQuery("SELECT id,txt,priority,complete, created, completed from todo where complete<>true;").use { rs ->
					while (rs.next()) {
						tdlist.add(loadTodoFromResultSet(rs))
					}
				}
				tdlist
			}
		}
	}

	fun getById(id: Int, dbConn: DbConn) : Todo {
		return dbConn.perform { con ->
			val todo = con.prepareStatement("SELECT id,txt,priority,complete, created, completed from todo where id=?;").use { stmt ->
				stmt.setInt(1, id)
				stmt.executeQuery().use { rs ->
					if (rs.next()) {
						loadTodoFromResultSet(rs)
					} else {
						throw RuntimeException("Note with id $id not found")
					}
				}
			}
			todo
		}
	}


	fun help() : String {
		return """
			td add {text}
			td list
			td complete {item number}
			td remove {item number}
		""".trimIndent()
	}

}