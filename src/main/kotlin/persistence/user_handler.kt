package persistence

import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.PreparedStatement
import java.sql.Timestamp
import java.time.Instant
import kotlin.experimental.and
import kotlin.math.roundToLong


internal object UserPersistence : DBUser() {
    private lateinit var getUserByUsername: PreparedStatement
    private lateinit var createUser: PreparedStatement
    private lateinit var getUserPassword: PreparedStatement
    private lateinit var getUserSalt: PreparedStatement
    private lateinit var storeSession: PreparedStatement
    private lateinit var getSessionInfo: PreparedStatement
    private lateinit var invalidateSessionID: PreparedStatement
    private lateinit var deleteUserSessions: PreparedStatement
    private lateinit var userIsAdmin: PreparedStatement
    private lateinit var makeAdmin: PreparedStatement

    override fun init() {
        ensureTablesExist()
        initPreparedStatements()
    }

    private fun ensureTablesExist() {
        connection().createStatement().execute("CREATE TABLE IF NOT EXISTS users(username VARCHAR(32) PRIMARY KEY, is_admin BOOLEAN DEFAULT FALSE, pw_hash VARCHAR(128) NOT NULL, salt VARCHAR(20) NOT NULL)")
        connection().createStatement().execute("CREATE TABLE IF NOT EXISTS sessions(token VARCHAR(128) PRIMARY KEY, username VARCHAR(32) NOT NULL, expiry TIMESTAMP NOT NULL)")
    }

    private fun initPreparedStatements() {
        getUserByUsername = connection().prepareStatement("SELECT * FROM users WHERE username = ?")
        createUser = connection().prepareStatement("INSERT INTO users (username, pw_hash, salt) VALUES (?,?,?)")
        getUserPassword = connection().prepareStatement("SELECT pw_hash FROM users WHERE username = ?")
        getUserSalt = connection().prepareStatement("SELECT salt FROM users WHERE username = ?")
        storeSession = connection().prepareStatement("INSERT INTO sessions (token, username, expiry) VALUES (?,?,?)")
        getSessionInfo = connection().prepareStatement("SELECT username, expiry FROM sessions WHERE token = ?")
        invalidateSessionID = connection().prepareStatement("DELETE FROM sessions WHERE token = ?")
        deleteUserSessions = connection().prepareStatement("DELETE FROM sessions WHERE username = ?")
        userIsAdmin = connection().prepareStatement("SELECT is_admin FROM users WHERE username = ?")
        makeAdmin = connection().prepareStatement("UPDATE users SET is_admin = TRUE WHERE username = ?")
    }

    fun createUser(username: String, password: String): Pair<Boolean, String?> = write {
        connection()
        if (userExists(username)) return@write false to "User already exists"
        val random = SecureRandom()
        val salt: String = (random.nextInt() * random.nextLong() - (random.nextDouble() * random.nextInt()).roundToLong()).toString(16)
        val hash = hashPassword(password, salt)
        createUser.setString(1, username)
        createUser.setString(2, hash)
        createUser.setString(3, salt)
        createUser.execute()
        createUser.clearParameters()
        return@write true to "User successfully created"
    }

    public fun userExists(user: String?, nullValue: Boolean = false): Boolean = read {
        connection()
        if (user == null) return@read nullValue
        getUserByUsername.setString(1, user)
        val ret: Boolean = getUserByUsername.executeQuery().next()
        getUserByUsername.clearParameters()
        return@read ret
    }

    private fun shutdown() = write {
        connection().commit()
        connection().close()
        println("DB shutdown")
    }

    private fun hashPassword(passwordToHash: String, salt: String): String {
        val generatedPassword: String
        val md = MessageDigest.getInstance("SHA-512")
        md.update(salt.toByteArray(Charsets.UTF_8))
        val bytes = md.digest(passwordToHash.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder()
        for (i in bytes.indices) {
            sb.append(Integer.toString((bytes[i] and 0xff.toByte()) + 0x100, 16).substring(1))
        }
        generatedPassword = sb.toString()

        return generatedPassword
    }

    fun verifyPassword(user: String, password: String): Boolean {
        connection()
        if (!userExists(user)) return false
        val salt = getSalt(user)
        val checkHash = getPasswordHash(user)
        val newHash = hashPassword(password, salt)

        return newHash == checkHash
    }

    private fun getPasswordHash(user: String): String = read {
        connection()
        if (!userExists(user)) throw IllegalArgumentException("User must exist")
        getUserPassword.setString(1, user)
        val resultSet = getUserPassword.executeQuery()
        resultSet.next()
        val password = resultSet.getString(1)
        getUserPassword.clearParameters()
        return@read password
    }

    private fun getSalt(user: String): String = read {
        connection()
        if (!userExists(user)) throw IllegalArgumentException("User must exist")
        getUserSalt.setString(1, user)
        val resultSet = getUserSalt.executeQuery()
        resultSet.next()
        val salt = resultSet.getString(1)
        getUserSalt.clearParameters()
        return@read salt
    }

    fun createToken(user: String, expiry: Timestamp): String = write {
        connection()
        clearUserTokens(user)
        val token = hashPassword("${getPasswordHash(user)} / $expiry", getSalt(user) + user)
        storeSession.setString(1, token)
        storeSession.setString(2, user)
        storeSession.setTimestamp(3, expiry)
        storeSession.execute()
        storeSession.clearParameters()
        return@write token
    }

    fun clearUserTokens(user: String) = write {
        connection()
        deleteUserSessions.setString(1, user)
        deleteUserSessions.execute()
        deleteUserSessions.clearParameters()
    }

    fun getUserFromToken(token: String?): String? = read {
        connection()
        if (token == null) return@read null
        getSessionInfo.setString(1, token)
        val result = getSessionInfo.executeQuery()
        getSessionInfo.clearParameters()
        if (result.next()) {
            val username = result.getString(1)
            val expiry = result.getTimestamp(2)
            if (expiry.toInstant().isBefore(Instant.now())) {
                invalidateToken(token)
                return@read null
            }
            return@read username
        } else return@read null
    }

    fun invalidateToken(token: String?) = write {
        connection()
        if (token == null) return@write
        invalidateSessionID.setString(1, token)
        invalidateSessionID.execute()
        invalidateSessionID.clearParameters()
    }

    fun isAdmin(username: String?): Boolean = read {
        connection()
        if (username == null) return@read false
        userIsAdmin.setString(1, username)
        val result = userIsAdmin.executeQuery()
        userIsAdmin.clearParameters()
        return@read if (result.next()) result.getBoolean(1) else false
    }

    fun makeAdmin(username: String) = write {
        connection()
        makeAdmin.setString(1, username)
        makeAdmin.execute()
        makeAdmin.clearParameters()
    }

    fun tokenIsAdmin(token: String?) = isAdmin(getUserFromToken(token))

    fun userCount(): Int = read {
        val resultSet = connection().createStatement().executeQuery("SELECT COUNT(username) FROM users")!!
        resultSet.next()

        return@read resultSet.getInt(1)
    }

}