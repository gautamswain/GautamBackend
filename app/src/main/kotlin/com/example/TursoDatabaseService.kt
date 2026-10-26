fun getColumns(): List<String> {
    val ds = dataSource ?: return emptyList()
    val result = mutableListOf<String>()
    var conn: Connection? = null
    try {
        conn = ds.connection
        val stmt = conn.createStatement()
        
        // 1. Database ରେ ଥିବା ସବୁ Tables ର ନାମ Check କରନ୍ତୁ
        val rsTables = stmt.executeQuery("SELECT name FROM sqlite_master WHERE type='table';")
        while (rsTables.next()) {
            result.add("TABLE: " + rsTables.getString("name"))
        }
        rsTables.close()

        // 2. MasterData / masterdata Table ର Columns Check କରନ୍ତୁ
        val rsCols = stmt.executeQuery("PRAGMA table_info('MasterData');")
        while (rsCols.next()) {
            result.add("COL: " + rsCols.getString("name"))
        }
        rsCols.close()
        stmt.close()
    } catch (e: Exception) {
        logger.error("Error: ${e.message}", e)
    } finally {
        conn?.close()
    }
    return result
}