package dev.arianna.storage

import dev.arianna.core.api.SnapshotStore
import dev.arianna.core.model.Confidence
import dev.arianna.core.model.EntityId
import dev.arianna.core.model.Evidence
import dev.arianna.core.model.KnowledgeEntity
import dev.arianna.core.model.KnowledgeRelation
import dev.arianna.core.model.Origin
import dev.arianna.core.model.Page
import dev.arianna.core.model.SnapshotKind
import dev.arianna.core.error.StorageException
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

class SQLiteKnowledgeStore(
    private val databaseFile: Path
) : SnapshotStore, AutoCloseable {
    companion object {
        private const val CURRENT_SCHEMA_VERSION = 3
    }

    data class SnapshotInfo(
        val id: Long,
        val repository: String,
        val revision: String,
        val kind: SnapshotKind,
        val current: Boolean
    )

    private val connection: Connection

    init {
        connection = try {
            Class.forName("org.sqlite.JDBC")
            DriverManager.getConnection("jdbc:sqlite:${databaseFile.toAbsolutePath()}").also {
                it.createStatement().use { statement -> statement.execute("PRAGMA foreign_keys = ON") }
            }
        } catch (error: Exception) {
            throw StorageException("Unable to open SQLite storage: $databaseFile", error)
        }
        try {
            initializeSchema()
        } catch (error: Exception) {
            connection.close()
            throw StorageException("Unable to initialize SQLite schema: $databaseFile", error)
        }
    }

    override fun replaceSnapshot(
        repository: String,
        revision: String,
        entities: Sequence<KnowledgeEntity>,
        relations: Sequence<KnowledgeRelation>
    ) {
        transaction {
            val snapshot = createSnapshot(repository, revision, SnapshotKind.BASELINE)
            entities.forEach { upsertEntity(snapshot.id, it) }
            relations.forEach { upsertRelation(snapshot.id, it) }
            publishSnapshot(snapshot.id)
        }
    }

    override fun replaceOverlaySnapshot(
        repository: String,
        revision: String,
        entities: Sequence<KnowledgeEntity>,
        relations: Sequence<KnowledgeRelation>
    ) {
        transaction {
            connection.prepareStatement("DELETE FROM snapshots WHERE repository = ? AND kind = ?").use { statement ->
                statement.setString(1, repository)
                statement.setString(2, SnapshotKind.WORKING_TREE.name.lowercase())
                statement.executeUpdate()
            }
            val snapshot = createSnapshot(repository, revision, SnapshotKind.WORKING_TREE)
            entities.forEach { upsertEntity(snapshot.id, it) }
            relations.forEach { upsertRelation(snapshot.id, it) }
        }
    }

    fun createSnapshot(repository: String, revision: String, kind: SnapshotKind = SnapshotKind.BASELINE): SnapshotInfo {
        val id = insertSnapshot(repository, revision, kind)
        return SnapshotInfo(id, repository, revision, kind, current = false)
    }

    fun publishSnapshot(snapshotId: Long) {
        val repository = connection.prepareStatement("SELECT repository, kind FROM snapshots WHERE id = ?").use { statement ->
            statement.setLong(1, snapshotId)
            statement.executeQuery().use { rows ->
                if (!rows.next()) throw StorageException("Snapshot non trovato: $snapshotId")
                if (rows.getString("kind") != SnapshotKind.BASELINE.name.lowercase()) {
                    throw StorageException("Only baseline snapshots can be published")
                }
                rows.getString("repository")
            }
        }
        connection.prepareStatement("UPDATE snapshots SET current = 0 WHERE repository = ? AND kind = ? AND id <> ?")
            .use { statement ->
                statement.setString(1, repository)
                statement.setString(2, SnapshotKind.BASELINE.name.lowercase())
                statement.setLong(3, snapshotId)
                statement.executeUpdate()
            }
        connection.prepareStatement("UPDATE snapshots SET current = 1 WHERE id = ?").use { statement ->
            statement.setLong(1, snapshotId)
            statement.executeUpdate()
        }
    }

    fun getCurrentSnapshot(repository: String): SnapshotInfo? =
        connection.prepareStatement(
            "SELECT id, repository, revision, kind, current FROM snapshots WHERE repository = ? AND kind = ? AND current = 1 ORDER BY id DESC LIMIT 1"
        ).use { statement ->
            statement.setString(1, repository)
            statement.setString(2, SnapshotKind.BASELINE.name.lowercase())
            statement.executeQuery().use { rows ->
                if (!rows.next()) null else SnapshotInfo(
                    rows.getLong("id"), rows.getString("repository"), rows.getString("revision"), SnapshotKind.valueOf(rows.getString("kind").uppercase()), rows.getBoolean("current")
                )
            }
        }

    fun getCurrentSnapshot(): SnapshotInfo? =
        connection.prepareStatement(
            "SELECT id, repository, revision, kind, current FROM snapshots WHERE kind = ? AND current = 1 ORDER BY id DESC LIMIT 1"
        ).use { statement ->
            statement.setString(1, SnapshotKind.BASELINE.name.lowercase())
            statement.executeQuery().use { rows ->
                if (!rows.next()) null else SnapshotInfo(
                    rows.getLong("id"), rows.getString("repository"), rows.getString("revision"), SnapshotKind.valueOf(rows.getString("kind").uppercase()), rows.getBoolean("current")
                )
            }
        }

    fun getLatestSnapshot(repository: String, kind: SnapshotKind): SnapshotInfo? =
        connection.prepareStatement(
            "SELECT id, repository, revision, kind, current FROM snapshots WHERE repository = ? AND kind = ? ORDER BY id DESC LIMIT 1"
        ).use { statement ->
            statement.setString(1, repository)
            statement.setString(2, kind.name.lowercase())
            statement.executeQuery().use { rows ->
                if (!rows.next()) null else SnapshotInfo(
                    rows.getLong("id"), rows.getString("repository"), rows.getString("revision"), SnapshotKind.valueOf(rows.getString("kind").uppercase()), rows.getBoolean("current")
                )
            }
        }

    override fun entitiesForSnapshot(snapshotId: Long): List<KnowledgeEntity> =
        connection.prepareStatement(
            "SELECT entity_id, kind, qualified_name, content, repository, revision, file, start_line, end_line, analyzer_version FROM entities WHERE snapshot_id = ? ORDER BY qualified_name"
        ).use { statement ->
            statement.setLong(1, snapshotId)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) add(
                        KnowledgeEntity(
                            id = EntityId(rows.getString("entity_id")),
                            kind = rows.getString("kind"),
                            qualifiedName = rows.getString("qualified_name"),
                            evidence = readEvidence(rows),
                            content = rows.getString("content")
                        )
                    )
                }
            }
        }

    override fun relationsForSnapshot(snapshotId: Long): List<KnowledgeRelation> =
        connection.prepareStatement(
            "SELECT source_id, relation_type, target_id, origin, confidence, repository, revision, file, start_line, end_line, analyzer_version FROM relations WHERE snapshot_id = ? ORDER BY source_id, relation_type, target_id"
        ).use { statement ->
            statement.setLong(1, snapshotId)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) add(
                        KnowledgeRelation(
                            source = EntityId(rows.getString("source_id")),
                            type = rows.getString("relation_type"),
                            target = EntityId(rows.getString("target_id")),
                            origin = Origin.valueOf(rows.getString("origin").uppercase()),
                            confidence = Confidence.valueOf(rows.getString("confidence").uppercase()),
                            evidence = readEvidence(rows)
                        )
                    )
                }
            }
        }

    fun upsertEntity(snapshotId: Long, entity: KnowledgeEntity) {
        connection.prepareStatement(
            """
            INSERT OR REPLACE INTO entities(snapshot_id, entity_id, kind, qualified_name,
                content, repository, revision, file, start_line, end_line, analyzer_version)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, snapshotId)
            statement.setString(2, entity.id.value)
            statement.setString(3, entity.kind)
            statement.setString(4, entity.qualifiedName)
            statement.setString(5, entity.content)
            bindEvidence(statement, 6, entity.evidence)
            statement.executeUpdate()
        }
    }

    fun upsertRelation(snapshotId: Long, relation: KnowledgeRelation) {
        connection.prepareStatement(
            """
            INSERT INTO relations(snapshot_id, source_id, relation_type, target_id,
                origin, confidence, repository, revision, file, start_line, end_line,
                analyzer_version)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, snapshotId)
            statement.setString(2, relation.source.value)
            statement.setString(3, relation.type)
            statement.setString(4, relation.target.value)
            statement.setString(5, relation.origin.name.lowercase())
            statement.setString(6, relation.confidence.name.lowercase())
            bindEvidence(statement, 7, relation.evidence)
            statement.executeUpdate()
        }
    }

    override fun saveEntities(entities: Sequence<KnowledgeEntity>) {
        throw StorageException("saveEntities without a snapshot is not supported; use replaceSnapshot")
    }

    override fun saveRelations(relations: Sequence<KnowledgeRelation>) {
        throw StorageException("saveRelations without a snapshot is not supported; use replaceSnapshot")
    }

    override fun findEntitiesPage(
        query: String,
        offset: Int,
        limit: Int,
        repository: String?,
        file: String?,
        kind: String?,
        revision: String?
    ): Page<KnowledgeEntity> {
        val result = mutableListOf<KnowledgeEntity>()
        val filters = entityFilters(query, repository, file, kind, revision)
        val total = countEntities(filters)
        connection.prepareStatement(
            """
            SELECT e.entity_id, e.kind, e.qualified_name, e.content, e.repository, e.revision, e.file,
                   e.start_line, e.end_line, e.analyzer_version
            FROM entities e JOIN snapshots s ON s.id = e.snapshot_id
            WHERE ${filters.first.joinToString(" AND ")}
            ORDER BY e.qualified_name, e.kind, e.entity_id LIMIT ? OFFSET ?
            """.trimIndent()
        ).use { statement ->
            var index = bindEntityFilters(statement, filters.second)
            statement.setInt(index++, limit)
            statement.setInt(index, offset)
            statement.executeQuery().use { rows ->
                while (rows.next()) {
                    result += KnowledgeEntity(
                        id = EntityId(rows.getString("entity_id")),
                        kind = rows.getString("kind"),
                        qualifiedName = rows.getString("qualified_name"),
                        evidence = readEvidence(rows),
                        content = rows.getString("content")
                    )
                }
            }
        }
        return Page(result, total, offset, limit)
    }

    override fun findRelationsPage(
        entityId: String,
        offset: Int,
        limit: Int,
        revision: String?,
        confidence: String?
    ): Page<KnowledgeRelation> {
        val result = mutableListOf<KnowledgeRelation>()
        val filters = relationFilters(entityId, revision, confidence)
        val total = countRelations(filters)
        connection.prepareStatement(
            """
            SELECT r.source_id, r.relation_type, r.target_id, r.origin, r.confidence,
                   r.repository, r.revision, r.file, r.start_line, r.end_line, r.analyzer_version
            FROM relations r JOIN snapshots s ON s.id = r.snapshot_id
            WHERE ${filters.first.joinToString(" AND ")}
            ORDER BY r.source_id, r.relation_type, r.target_id, COALESCE(r.file, ''), COALESCE(r.start_line, 0)
            LIMIT ? OFFSET ?
            """.trimIndent()
        ).use { statement ->
            var index = bindEntityFilters(statement, filters.second)
            statement.setInt(index++, limit)
            statement.setInt(index, offset)
            statement.executeQuery().use { rows ->
                while (rows.next()) {
                    result += KnowledgeRelation(
                        source = EntityId(rows.getString("source_id")),
                        type = rows.getString("relation_type"),
                        target = EntityId(rows.getString("target_id")),
                        origin = Origin.valueOf(rows.getString("origin").uppercase()),
                        confidence = Confidence.valueOf(rows.getString("confidence").uppercase()),
                        evidence = readEvidence(rows)
                    )
                }
            }
        }
        return Page(result, total, offset, limit)
    }

    private fun countEntities(filters: Pair<List<String>, List<String>>): Int = connection.prepareStatement(
        "SELECT COUNT(*) FROM entities e JOIN snapshots s ON s.id = e.snapshot_id WHERE ${filters.first.joinToString(" AND ")}"
    ).use { statement ->
        bindEntityFilters(statement, filters.second)
        statement.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
    }

    private fun entityFilters(query: String, repository: String?, file: String?, kind: String?, revision: String?): Pair<List<String>, List<String>> {
        val tokens = query.trim().split(Regex("\\s+")).filter(String::isNotEmpty).ifEmpty { listOf("") }
        val clauses = mutableListOf("s.current = 1")
        val values = mutableListOf<String>()
        tokens.forEach { token ->
            clauses += "(e.qualified_name LIKE ? OR e.file LIKE ? OR e.content LIKE ?)"
            values += "%$token%"
            values += "%$token%"
            values += "%$token%"
        }
        repository?.let { clauses += "e.repository = ?"; values += it }
        file?.let { clauses += "e.file LIKE ?"; values += "%$it%" }
        kind?.let { clauses += "e.kind = ?"; values += it }
        revision?.let { clauses += "e.revision = ?"; values += it }
        return clauses to values
    }

    private fun bindEntityFilters(statement: java.sql.PreparedStatement, values: List<String>): Int {
        values.forEachIndexed { index, value -> statement.setString(index + 1, value) }
        return values.size + 1
    }

    private fun relationFilters(entityId: String, revision: String?, confidence: String?): Pair<List<String>, List<String>> {
        val clauses = mutableListOf("s.current = 1", "(r.source_id = ? OR r.target_id = ?)")
        val values = mutableListOf(entityId, entityId)
        revision?.let { clauses += "r.revision = ?"; values += it }
        confidence?.let { clauses += "r.confidence = ?"; values += it.lowercase() }
        return clauses to values
    }

    private fun countRelations(filters: Pair<List<String>, List<String>>): Int = connection.prepareStatement(
        "SELECT COUNT(*) FROM relations r JOIN snapshots s ON s.id = r.snapshot_id WHERE ${filters.first.joinToString(" AND ")}"
    ).use { statement ->
        bindEntityFilters(statement, filters.second)
        statement.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
    }

    override fun close() {
        connection.close()
    }

    fun currentRevision(repository: String): String? {
        connection.prepareStatement(
            "SELECT revision FROM snapshots WHERE repository = ? AND kind = ? AND current = 1 ORDER BY id DESC LIMIT 1"
        ).use { statement ->
            statement.setString(1, repository)
            statement.setString(2, SnapshotKind.BASELINE.name.lowercase())
            statement.executeQuery().use { rows ->
                return if (rows.next()) rows.getString("revision") else null
            }
        }
    }

    private fun initializeSchema() {
        connection.createStatement().use { statement ->
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)")
        }
        val schemaVersion = connection.createStatement().use { statement ->
            statement.executeQuery("SELECT version FROM schema_version ORDER BY version DESC LIMIT 1").use { rows ->
                if (rows.next()) rows.getInt("version") else 0
            }
        }
        when {
            schemaVersion == 0 -> {
                createSchemaV2()
                connection.prepareStatement("INSERT INTO schema_version(version) VALUES (?)").use { statement ->
                    statement.setInt(1, CURRENT_SCHEMA_VERSION)
                    statement.executeUpdate()
                }
            }
            schemaVersion == 1 -> {
                migrateV1ToV2()
                migrateV2ToV3()
                connection.prepareStatement("UPDATE schema_version SET version = ?").use { statement ->
                    statement.setInt(1, CURRENT_SCHEMA_VERSION)
                    statement.executeUpdate()
                }
            }
            schemaVersion == 2 -> {
                migrateV2ToV3()
                connection.prepareStatement("UPDATE schema_version SET version = ?").use { statement ->
                    statement.setInt(1, CURRENT_SCHEMA_VERSION)
                    statement.executeUpdate()
                }
            }
            schemaVersion == CURRENT_SCHEMA_VERSION -> createSchemaV2()
            schemaVersion > CURRENT_SCHEMA_VERSION -> throw StorageException(
                "Schema SQLite $schemaVersion non supportato; versione massima: $CURRENT_SCHEMA_VERSION"
            )
            else -> throw StorageException("Schema SQLite $schemaVersion non migrabile")
        }
    }

    private fun createSchemaV2() {
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS snapshots (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    repository TEXT NOT NULL,
                    revision TEXT NOT NULL,
                    kind TEXT NOT NULL DEFAULT 'baseline',
                    current INTEGER NOT NULL DEFAULT 0,
                    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """.trimIndent()
            )
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS entities (
                    snapshot_id INTEGER NOT NULL REFERENCES snapshots(id) ON DELETE CASCADE,
                    entity_id TEXT NOT NULL,
                    kind TEXT NOT NULL,
                    qualified_name TEXT NOT NULL,
                    content TEXT,
                    repository TEXT,
                    revision TEXT,
                    file TEXT,
                    start_line INTEGER,
                    end_line INTEGER,
                    analyzer_version TEXT,
                    PRIMARY KEY(snapshot_id, entity_id)
                )
                """.trimIndent()
            )
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS relations (
                    snapshot_id INTEGER NOT NULL REFERENCES snapshots(id) ON DELETE CASCADE,
                    source_id TEXT NOT NULL,
                    relation_type TEXT NOT NULL,
                    target_id TEXT NOT NULL,
                    origin TEXT NOT NULL,
                    confidence TEXT NOT NULL,
                    repository TEXT,
                    revision TEXT,
                    file TEXT,
                    start_line INTEGER,
                    end_line INTEGER,
                    analyzer_version TEXT
                )
                """.trimIndent()
            )
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_entities_name ON entities(qualified_name)")
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_relations_source ON relations(source_id)")
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_relations_target ON relations(target_id)")
        }
    }

    private fun migrateV1ToV2() {
        connection.createStatement().use { statement ->
            statement.executeUpdate("ALTER TABLE entities ADD COLUMN content TEXT")
        }
        createSchemaV2()
    }

    private fun migrateV2ToV3() {
        connection.createStatement().use { statement ->
            statement.executeUpdate("ALTER TABLE snapshots ADD COLUMN kind TEXT NOT NULL DEFAULT 'baseline'")
        }
    }

    private fun insertSnapshot(repository: String, revision: String, kind: SnapshotKind): Long {
        connection.prepareStatement(
            "INSERT INTO snapshots(repository, revision, kind, current) VALUES (?, ?, ?, 0)",
            java.sql.Statement.RETURN_GENERATED_KEYS
        ).use { statement ->
            statement.setString(1, repository)
            statement.setString(2, revision)
            statement.setString(3, kind.name.lowercase())
            statement.executeUpdate()
            statement.generatedKeys.use { keys ->
                if (!keys.next()) throw StorageException("SQLite non ha restituito l'id dello snapshot")
                return keys.getLong(1)
            }
        }
    }

    private fun transaction(block: () -> Unit) {
        try {
            connection.autoCommit = false
            block()
            connection.commit()
        } catch (error: Exception) {
            connection.rollback()
            throw StorageException("Transazione SQLite fallita", error)
        } finally {
            connection.autoCommit = true
        }
    }

    private fun bindEvidence(statement: java.sql.PreparedStatement, offset: Int, evidence: Evidence?) {
        statement.setString(offset, evidence?.repository)
        statement.setString(offset + 1, evidence?.revision)
        statement.setString(offset + 2, evidence?.file)
        if (evidence?.startLine == null) statement.setObject(offset + 3, null) else statement.setInt(offset + 3, evidence.startLine)
        if (evidence?.endLine == null) statement.setObject(offset + 4, null) else statement.setInt(offset + 4, evidence.endLine)
        statement.setString(offset + 5, evidence?.analyzerVersion)
    }

    private fun readEvidence(rows: java.sql.ResultSet): Evidence? {
        val repository = rows.getString("repository") ?: return null
        return Evidence(
            repository = repository,
            revision = rows.getString("revision"),
            file = rows.getString("file"),
            startLine = (rows.getObject("start_line") as? Number)?.toInt(),
            endLine = (rows.getObject("end_line") as? Number)?.toInt(),
            analyzerVersion = rows.getString("analyzer_version")
        )
    }
}
