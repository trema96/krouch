/*
 *    Copyright 2020 Taktik SA
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 *
 */

package org.taktik.couchdb

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import io.icure.asyncjacksonhttpclient.net.web.HttpMethod
import io.icure.asyncjacksonhttpclient.netty.NettyWebClient
import io.icure.asyncjacksonhttpclient.parser.EndArray
import io.icure.asyncjacksonhttpclient.parser.StartArray
import io.icure.asyncjacksonhttpclient.parser.StartObject
import io.icure.asyncjacksonhttpclient.parser.split
import io.icure.asyncjacksonhttpclient.parser.toJsonEvents
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.fold
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.taktik.couchdb.dao.CodeDAO
import org.taktik.couchdb.entity.*
import org.taktik.couchdb.exception.CouchDbConflictException
import org.taktik.couchdb.exception.CouchDbException
import reactor.tools.agent.ReactorDebugAgent
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.util.*
import kotlin.random.Random

@FlowPreview
@ExperimentalCoroutinesApi
class CouchDbClientTests {
    private val log = org.slf4j.LoggerFactory.getLogger(this::class.java)

    private val databaseHost =  System.getProperty("krouch.test.couchdb.server.url", "http://localhost:5984")
    private val databaseName =  System.getProperty("krouch.test.couchdb.database.name", "krouch-test")
    private val userName = System.getProperty("krouch.test.couchdb.username", "admin")
    private val password = System.getProperty("krouch.test.couchdb.password", "password")

    private val testResponseAsString = URL("https://jsonplaceholder.typicode.com/posts").openStream().use { it.readBytes().toString(StandardCharsets.UTF_8) }
    private val httpClient = NettyWebClient()
    private val client = ClientImpl(
        httpClient,
            URI("$databaseHost/$databaseName"),
            userName,
            password)

    private val testDAO = CodeDAO(client)

    init {
        ReactorDebugAgent.init()
    }

    @BeforeEach
    fun setupDatabase() {
        //  Setup the Database and DesignDocument (via the DAO interface)
        runBlocking {
            if(!client.exists()){
                client.create(8, 2)
            }
            try {
                testDAO.createOrUpdateDesignDocument()
            } catch (e: Exception) {}
        }
    }

    @Test
    fun testSubscribeChanges() = runBlocking {
        val testSize = 10
        var testsDone = 0
        val deferredChanges = async {
            client.subscribeForChanges<Code>("java_type", {
                if (it == "Code") Code::class.java else null
            }).onEach {
                log.warn("Read code ${++testsDone}/$testSize")
            }.take(testSize).toList()
        }
        // Wait a bit before updating DB
        val codes = List(testSize) { Code.from("test", UUID.randomUUID().toString(), "test") }
        val createdCodes = codes.map {
            delay(300)
            client.update(it)
        }
        val changes = deferredChanges.await()
        assertEquals(createdCodes.size, changes.size)
        assertEquals(createdCodes.map { it.id }.toSet(), changes.map { it.id }.toSet())
        assertEquals(codes.map { it.code }.toSet(), changes.map { it.doc.code }.toSet())
    }

    @Test
    fun testSubscribeChangesHeartbeat() = runBlocking {
        val testSize = 1
        var testsDone = 0
        val deferredChanges = async {
            client.subscribeForChanges<Code>("java_type", {
                if (it == "Code") Code::class.java else null
            }).onEach {
                log.warn("Read code ${++testsDone}/$testSize")
            }.take(testSize).toList()
        }
        // Wait a bit before updating DB
        val codes = List(testSize) { Code.from("test", UUID.randomUUID().toString(), "test") }
        val createdCodes = codes.map {
            delay(45000)
            client.update(it)
        }
        val changes = withTimeout(50000) { deferredChanges.await() }
        assertEquals(createdCodes.size, changes.size)
        assertEquals(createdCodes.map { it.id }.toSet(), changes.map { it.id }.toSet())
        assertEquals(codes.map { it.code }.toSet(), changes.map { it.doc.code }.toSet())
    }

    @Test
    fun testSubscribeUserChanges() = runBlocking {
        val testSize = 100
        val deferredChanges = async {
            client.subscribeForChanges("java_type", {
                if (it == "org.taktik.icure.entities.User") {
                    User::class.java
                } else null
            }, "0").map { it.also {
                println("${it.doc.id}:${it.doc.login}")
            } }.take(testSize).toList()
        }

        val changes = withTimeout(5000) { deferredChanges.await() }
        assertTrue(changes.isNotEmpty())
    }

    @Test
    fun testExists() = runBlocking {
        assertTrue(client.exists())
    }

    @Test
    fun testDestroyDatabase() = runBlocking {
        val client = ClientImpl(
            httpClient,
            URI("$databaseHost/test_${UUID.randomUUID()}"),
            userName,
            password)
        client.create(1,1)
        delay(1000L)
        assertTrue(client.destroyDatabase())
    }

    @Test
    fun testExists2() = runBlocking {
        val client = ClientImpl(
                httpClient,
                URI("$databaseHost/${UUID.randomUUID()}"),
                userName,
                password)
        assertFalse(client.exists())
    }

    @Test
    fun testRequestGetResponseBytesFlow() = runBlocking {
        val bytesFlow = httpClient.uri("https://jsonplaceholder.typicode.com/posts").method(HttpMethod.GET).retrieve().toBytesFlow()

        val bytes = bytesFlow.fold(ByteBuffer.allocate(1000000), { acc, buffer -> acc.put(buffer) })
        bytes.flip()
        val responseAsString = StandardCharsets.UTF_8.decode(bytes).toString()
        assertEquals(testResponseAsString, responseAsString)
    }

    @Test
    fun testRequestGetText() = runBlocking {
        val charBuffers = httpClient.uri("https://jsonplaceholder.typicode.com/posts").method(HttpMethod.GET).retrieve().toTextFlow()
        val chars = charBuffers.toList().fold(CharBuffer.allocate(1000000), { acc, buffer -> acc.put(buffer) })
        chars.flip()
        assertEquals(testResponseAsString, chars.toString())
    }

    @Test
    fun testRequestGetTextAndSplit() = runBlocking {
        val charBuffers = httpClient.uri("https://jsonplaceholder.typicode.com/posts").method(HttpMethod.GET).retrieve().toTextFlow()
        val split = charBuffers.split('\n')
        val lines = split.map { it.fold(CharBuffer.allocate(100000), { acc, buffer -> acc.put(buffer) }).flip().toString() }.toList()
        assertEquals(testResponseAsString.split("\n"), lines)
    }

    @Test
    fun testRequestGetJsonEvent() = runBlocking {
        val asyncParser = ObjectMapper().also { it.registerModule(KotlinModule()) }.createNonBlockingByteArrayParser()

        val bytes = httpClient.uri("https://jsonplaceholder.typicode.com/posts").method(HttpMethod.GET).retrieve().toBytesFlow()
        val jsonEvents = bytes.toJsonEvents(asyncParser).toList()
        assertEquals(StartArray, jsonEvents.first(), "Should start with StartArray")
        assertEquals(StartObject, jsonEvents[1], "jsonEvents[1] == StartObject")
        assertEquals(EndArray, jsonEvents.last(), "Should end with EndArray")
    }

    @Test
    fun testClientQueryViewIncludeDocs() = runBlocking {
        val limit = 5
        val query = ViewQuery()
                .designDocId("_design/Code")
                .viewName("all")
                .limit(limit)
                .includeDocs(true)
        val flow = client.queryViewIncludeDocs<String, String, Code>(query)
        val codes = flow.toList()
        assertEquals(limit, codes.size)
    }

    @Test
    fun testClientQueryViewNoDocs() = runBlocking {
        val limit = 5
        val query = ViewQuery()
                .designDocId("_design/Code")
                .viewName("all")
                .limit(limit)
                .includeDocs(false)
        val flow = client.queryView<String, String>(query)
        val codes = flow.toList()
        assertEquals(limit, codes.size)
    }

    @Test
    fun testRawClientQuery() = runBlocking {
        val limit = 5
        val query = ViewQuery()
                .designDocId("_design/Code")
                .viewName("all")
                .limit(limit)
                .includeDocs(false)
        val flow = client.queryView(query, String::class.java, String::class.java, Nothing::class.java)

        val events = flow.toList()
        assertEquals(1, events.filterIsInstance<TotalCount>().size)
        assertEquals(1, events.filterIsInstance<Offset>().size)
        assertEquals(limit, events.filterIsInstance<ViewRow<*, *, *>>().size)
    }

    @Test
    fun testClientGetNonExisting() = runBlocking {
        val nonExistingId = UUID.randomUUID().toString()
        val code = client.get<Code>(nonExistingId)
        assertNull(code)
    }

    @Test
    fun testClientGetDbsInfo() = runBlocking {
        val dbs = client.databaseInfos(client.allDatabases()).toList()
        assertTrue(dbs.isNotEmpty())
    }

    @Test
    fun testClientAllDatabases() = runBlocking {
        val dbs = client.allDatabases().toList()
        assertTrue(dbs.isNotEmpty())
    }

    @Test
    fun testClientCreateAndGet() = runBlocking {
        val randomCode = UUID.randomUUID().toString()
        val toCreate = Code.from("test", randomCode, "test")
        val created = client.create(toCreate)
        assertEquals(randomCode, created.code)
        assertNotNull(created.id)
        assertNotNull(created.rev)
        val fetched = checkNotNull(client.get<Code>(created.id)) { "Code was just created, it should exist" }
        assertEquals(fetched.id, created.id)
        assertEquals(fetched.code, created.code)
        assertEquals(fetched.rev, created.rev)
    }

    @Test
    fun testClientUpdate() = runBlocking {
        val randomCode = UUID.randomUUID().toString()
        val toCreate = Code.from("test", randomCode, "test")
        val created = client.create(toCreate)
        assertEquals(randomCode, created.code)
        assertNotNull(created.id)
        assertNotNull(created.rev)
        // update code
        val anotherRandomCode = UUID.randomUUID().toString()
        val updated = client.update(created.copy(code = anotherRandomCode))
        assertEquals(created.id, updated.id)
        assertEquals(anotherRandomCode, updated.code)
        assertNotEquals(created.rev, updated.rev)
        val fetched = checkNotNull(client.get<Code>(updated.id))
        assertEquals(fetched.id, updated.id)
        assertEquals(fetched.code, updated.code)
        assertEquals(fetched.rev, updated.rev)
    }

    @Test
    fun testClientUpdateOutdated() {
        Assertions.assertThrows(CouchDbConflictException::class.java) {
            runBlocking {
                val randomCode = UUID.randomUUID().toString()
                val toCreate = Code.from("test", randomCode, "test")
                val created = client.create(toCreate)
                assertEquals(randomCode, created.code)
                assertNotNull(created.id)
                assertNotNull(created.rev)
                // update code
                val anotherRandomCode = UUID.randomUUID().toString()
                val updated = client.update(created.copy(code = anotherRandomCode))
                assertEquals(created.id, updated.id)
                assertEquals(anotherRandomCode, updated.code)
                assertNotEquals(created.rev, updated.rev)
                val fetched = checkNotNull(client.get<Code>(updated.id))
                assertEquals(fetched.id, updated.id)
                assertEquals(fetched.code, updated.code)
                assertEquals(fetched.rev, updated.rev)
                // Should throw a Document update conflict Exception
                @Suppress("UNUSED_VARIABLE")
                val updateResult = client.update(created)
            }
        }
    }

    @Test
    fun testClientDelete() = runBlocking {
        val randomCode = UUID.randomUUID().toString()
        val toCreate = Code.from("test", randomCode, "test")
        val created = client.create(toCreate)
        assertEquals(randomCode, created.code)
        assertNotNull(created.id)
        assertNotNull(created.rev)
        val deletedRev = client.delete(created)
        assertNotEquals(created.rev, deletedRev)
        assertNull(client.get<Code>(created.id))
    }

    @Test
    fun testClientBulkGet() = runBlocking {
        val limit = 100
        val query = ViewQuery()
                .designDocId("_design/Code")
                .viewName("by_type")
                .limit(limit)
                .includeDocs(true)
        val flow = client.queryViewIncludeDocs<List<*>, Int, Code>(query)
        val codes = flow.map { it.doc }.toList()
        val codeIds = codes.map { it.id }
        val flow2 = client.get<Code>(codeIds)
        val codes2 = flow2.toList()
        assertEquals(codes, codes2)
    }

    @Test
    fun testClientBulkUpdate() = runBlocking {
        val testSize = 100
        val codes = List(testSize) { Code.from("test", UUID.randomUUID().toString(), "test") }
        val updateResult = client.bulkUpdate(codes).toList()
        assertEquals(testSize, updateResult.size)
        assertTrue(updateResult.all { it.error == null })
        val revisions = updateResult.map { checkNotNull(it.rev) }
        val ids = codes.map { it.id }
        val codeCodes = codes.map { it.code }
        val fetched = client.get<Code>(ids).toList()
        assertEquals(codeCodes, fetched.map { it.code })
        assertEquals(revisions, fetched.map { it.rev })
    }

    @Test
    fun testBasicDAOQuery() = runBlocking {
        val codes = testDAO.findCodeByTypeAndVersion("test", "test").map { it.doc }.toList()
        val fetched = client.get<Code>(codes.map { it.id }).toList()
        assertEquals(codes.map { it.code }, fetched.map { it.code })
    }

    @Test
    fun testReplicateCommands() = runBlocking {
        if (client.getCouchDBVersion() >= "3.2.0") {
            val oneTimeCmd = ReplicateCommand.oneTime(
                    sourceUrl = URI("${databaseHost}/${databaseName}"),
                    sourceUsername = userName,
                    sourcePassword = password,
                    targetUrl = URI("${databaseHost}/${databaseName}_one_time"),
                    targetUsername = userName,
                    targetPassword = password,
                    id = "${databaseName}_one_time"
            )

            val continuousCmd = ReplicateCommand.continuous(
                    sourceUrl = URI("${databaseHost}/${databaseName}"),
                    sourceUsername = userName,
                    sourcePassword = password,
                    targetUrl = URI("${databaseHost}/${databaseName}_continuous"),
                    targetUsername = userName,
                    targetPassword = password,
                    id = "${databaseName}_continuous"
            )
            val oneTimeResponse = client.replicate(oneTimeCmd)
            assertTrue(oneTimeResponse.ok)

            val continuousResponse = client.replicate(continuousCmd)
            assertTrue(continuousResponse.ok)

            val schedulerDocsResponse = client.schedulerDocs()
            assertTrue(schedulerDocsResponse.docs.size >= 2)

            val schedulerJobsResponse = client.schedulerJobs()
            assertTrue(schedulerJobsResponse.jobs.isNotEmpty())

            schedulerDocsResponse.docs
                    .filter { it.docId == oneTimeCmd.id || it.docId == continuousCmd.id }
                    .forEach {
                        val cancelResponse = client.deleteReplication(it.docId!!)
                        assertTrue(cancelResponse.ok)
                    }
        }
    }

    @Test
    fun testActiveTasksInstanceMapper() = runBlocking {
        val activeTasksSample = File(javaClass.classLoader.getResource("active_tasks_sample.json")!!.file)
                .readText()
                .replace("\n".toRegex(), "")
        val kotlinMapper = ObjectMapper().also { it.registerModule(KotlinModule()) }
        val activeTasks: List<ActiveTask> = kotlinMapper.readValue(activeTasksSample)

        assertTrue(activeTasks[0] is Indexer)
        assertTrue(activeTasks[1] is ViewCompactionTask)
        assertTrue(activeTasks[2] is DatabaseCompactionTask)
        assertTrue(activeTasks[4] is ReplicationTask)
    }

    @Test
    fun testCreateAndGetAttachment() = runBlocking {
        val randomCode = UUID.randomUUID().toString()
        val created = client.create(Code.from("test", randomCode, "test"))
        val attachmentId = "attachment1"
        val attachment = byteArrayOf(1, 2, 3)
        client.createAttachment(
            created.id,
            attachmentId,
            created.rev!!,
            "application/json",
            flowOf(ByteBuffer.wrap(attachment))
        )
        val retrievedAttachment = ByteArrayOutputStream().use { os ->
            @Suppress("BlockingMethodInNonBlockingContext")
            client.getAttachment(created.id, attachmentId).collect { bb ->
                if (bb.hasArray() && bb.hasRemaining()) {
                    os.write(bb.array(), bb.position() + bb.arrayOffset(), bb.remaining())
                } else {
                    os.write(ByteArray(bb.remaining()).also { bb.get(it) })
                }
            }
            os.toByteArray()
        }
        assertEquals(retrievedAttachment.toList(), attachment.toList())
        try {
            client.getAttachment(created.id, "non-existing").first()
            fail("Should not be able to retrieve non-existing attachment")
        } catch (e: CouchDbException) {
            assertEquals(e.statusCode, 404)
        }
    }

    fun testAttachmentSize() = runBlocking {
        val created = client.create(Code.from("test", UUID.randomUUID().toString(), "test"))
        val sizes = listOf(10, 50, 100, 500, 1000, 1234).map { "attachment$it" to it }
        sizes.fold(created.rev!!) { rev, (id, size) ->
            val attachment = Random(System.currentTimeMillis()).nextBytes(size)
            client.createAttachment(
                created.id,
                id,
                rev,
                "application/json",
                flowOf(ByteBuffer.wrap(attachment))
            )
        }
        val codeWithAttachments = client.get<Code>(created.id)!!
        assertEquals(codeWithAttachments.attachments?.size, sizes.size)
        assertEquals(codeWithAttachments.attachments?.map { it.key to it.value.length?.toInt() }?.toSet(), sizes.toSet())
    }
}
