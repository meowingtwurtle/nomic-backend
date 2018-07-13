package persistence

import java.sql.PreparedStatement

internal object PointsPersistence : DBUser() {
    private lateinit var getUserPoints: PreparedStatement
    private lateinit var getAllPoints: PreparedStatement
    private lateinit var setUserPoints: PreparedStatement

    override fun init() {
        ensureTablesExist()
    }

    private fun ensureTablesExist() {
        connection().createStatement().execute("CREATE TABLE IF NOT EXISTS points(username VARCHAR(32) PRIMARY KEY, points BIGINT NOT NULL)")
    }

    private fun initPreparedStatements() {
        getUserPoints = connection().prepareStatement("SELECT points FROM points WHERE username = ?")
        getAllPoints = connection().prepareStatement("SELECT * FROM points")
        setUserPoints = connection().prepareStatement("MERGE INTO points USING (VALUES(?,?)) AS newvals(username, points) ON points.username = newvals.username WHEN MATCHED THEN UPDATE SET points.username = newvals.username WHEN NOT MATCHED THEN INSERT VALUES newvals.username, newvals.points")
    }

    fun getUserPoints(username: String): Int = read {
        getUserPoints.setString(1, username)
        getUserPoints.clearParameters()
        val resultSet = getUserPoints.executeQuery()!!
        if (!resultSet.next()) return@read 0
        return@read resultSet.getInt("points")
    }

    fun getAllPoints(): Map<String, Int> = read {
        val resultSet = getAllPoints.executeQuery()!!
        val map = mutableMapOf<String, Int>()

        while (resultSet.next()) {
            map[resultSet.getString("username")] = resultSet.getInt("points")
        }

        return@read map
    }

    fun setUserPoints(username: String, points: Int) = read {
        setUserPoints.setString(1, username)
        setUserPoints.setInt(2, points)
        setUserPoints.execute()
        setUserPoints.clearParameters()
    }
}