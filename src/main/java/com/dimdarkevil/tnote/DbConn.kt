package com.dimdarkevil.tnote

import org.slf4j.LoggerFactory
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

class DbConn(val dbFile: File) {
	private val log = LoggerFactory.getLogger(DbConn::class.java)
	private val con: Connection = DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}")

	init {
		con.autoCommit = false
	}

	fun close() {
		con.close()
	}

	fun getConnection(): Connection {
		return con
	}

	fun transact(func: (Connection) -> Unit) {
		getConnection().let { connection ->
			connection.autoCommit = false
			try {
				func(connection)
				connection.commit()
			} catch (e: Exception) {
				log.error(e.message, e)
				connection.rollback()
				throw e
			}
		}
	}

	fun <R> perform(func: (Connection) -> R) : R {
		getConnection().let { connection ->
			connection.autoCommit = false
			try {
				val ret = func(connection)
				connection.commit()
				return ret
			} catch (e: Exception) {
				log.error(e.message, e)
				connection.rollback()
				throw e
			}
		}
	}

}