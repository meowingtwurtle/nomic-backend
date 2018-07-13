package persistence

import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

private object DBHandler {
    private var initDone = false
    private var initInProgress = false

    private lateinit var dbConnection: Connection
    private val lock = ReentrantReadWriteLock()

    internal fun connection(): Connection {
        if (!initDone) init()
        return dbConnection
    }

    private fun init() {
        while (initInProgress) {}

        if (initDone) return
        initInProgress = true

        Class.forName("org.hsqldb.jdbc.JDBCDriver")
        dbConnection = DriverManager.getConnection("jdbc:hsqldb:file:nomicdb;shutdown=true", "SA", "")

        initDone = true
        initInProgress = false

        Runtime.getRuntime().addShutdownHook(object : Thread() {
            override fun run() {
                shutdown()
            }
        })
    }

    private fun shutdown() = write {
        connection().close()
        println("DB shutdown")
    }

    public fun <T> read(block: () -> T): T {
        if (!initDone) init()

        return lock.read(block)
    }

    public fun <T> write(block: () -> T): T {
        if (!initDone) init()

        return lock.write {
            val ret = block()

//            if (!connection().isClosed) connection().commit()
            return@write ret // For lambda, not method, can't avoid confusion
        }
    }
}

internal abstract class DBUser {
    private var initDone = false
    private var initInProgress = false
    private var initThread: Thread? = null

    protected abstract fun init()

    protected final fun connection(): Connection {
        val superConnection = DBHandler.connection()

        while (initInProgress && Thread.currentThread() != initThread) {}

        if (!initDone) {
            handleInit()
        }

        return superConnection
    }

    private final fun handleInit() {
        if (initDone) return
        if (initThread == Thread.currentThread()) return

        initInProgress = true

        initThread = Thread.currentThread()

        init()

        initInProgress = false
        initDone = true
    }

    protected final fun <T> write(block: () -> T): T = DBHandler.write(block)
    protected final fun <T> read(block: () -> T): T = DBHandler.read(block)
}