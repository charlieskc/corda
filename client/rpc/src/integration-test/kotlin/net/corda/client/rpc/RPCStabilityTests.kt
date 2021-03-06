package net.corda.client.rpc

import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.Serializer
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import com.esotericsoftware.kryo.pool.KryoPool
import com.google.common.base.Stopwatch
import com.google.common.net.HostAndPort
import com.google.common.util.concurrent.Futures
import net.corda.client.rpc.internal.RPCClient
import net.corda.client.rpc.internal.RPCClientConfiguration
import net.corda.core.*
import net.corda.core.messaging.RPCOps
import net.corda.node.driver.poll
import net.corda.node.services.messaging.RPCServerConfiguration
import net.corda.nodeapi.RPCApi
import net.corda.nodeapi.RPCKryo
import net.corda.testing.*
import org.apache.activemq.artemis.ArtemisConstants
import org.apache.activemq.artemis.api.core.SimpleString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import rx.Observable
import rx.subjects.PublishSubject
import rx.subjects.UnicastSubject
import java.time.Duration
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread


class RPCStabilityTests {

    object DummyOps : RPCOps {
        override val protocolVersion = 0
    }

    private fun waitUntilNumberOfThreadsStable(executorService: ScheduledExecutorService): Int {
        val values = ConcurrentLinkedQueue<Int>()
        return poll(executorService, "number of threads to become stable", 250.millis) {
            values.add(Thread.activeCount())
            if (values.size > 5) {
                values.poll()
            }
            val first = values.peek()
            if (values.size == 5 && values.all { it == first }) {
                first
            } else {
                null
            }
        }.get()
    }

    @Test
    fun `client and server dont leak threads`() {
        val executor = Executors.newScheduledThreadPool(1)
        fun startAndStop() {
            rpcDriver {
                val server = startRpcServer<RPCOps>(ops = DummyOps)
                startRpcClient<RPCOps>(server.get().broker.hostAndPort!!).get()
            }
        }
        repeat(5) {
            startAndStop()
        }
        val numberOfThreadsBefore = waitUntilNumberOfThreadsStable(executor)
        repeat(5) {
            startAndStop()
        }
        val numberOfThreadsAfter = waitUntilNumberOfThreadsStable(executor)
        // This is a less than check because threads from other tests may be shutting down while this test is running.
        // This is therefore a "best effort" check. When this test is run on its own this should be a strict equality.
        assertTrue(numberOfThreadsBefore >= numberOfThreadsAfter)
        executor.shutdownNow()
    }

    @Test
    fun `client doesnt leak threads when it fails to start`() {
        val executor = Executors.newScheduledThreadPool(1)
        fun startAndStop() {
            rpcDriver {
                ErrorOr.catch { startRpcClient<RPCOps>(HostAndPort.fromString("localhost:9999")).get() }
                val server = startRpcServer<RPCOps>(ops = DummyOps)
                ErrorOr.catch { startRpcClient<RPCOps>(
                        server.get().broker.hostAndPort!!,
                        configuration = RPCClientConfiguration.default.copy(minimumServerProtocolVersion = 1)
                ).get() }
            }
        }
        repeat(5) {
            startAndStop()
        }
        val numberOfThreadsBefore = waitUntilNumberOfThreadsStable(executor)
        repeat(5) {
            startAndStop()
        }
        val numberOfThreadsAfter = waitUntilNumberOfThreadsStable(executor)
        assertTrue(numberOfThreadsBefore >= numberOfThreadsAfter)
        executor.shutdownNow()
    }

    fun RpcBrokerHandle.getStats(): Map<String, Any> {
        return serverControl.run {
            mapOf(
                    "connections" to listConnectionIDs().toSet(),
                    "sessionCount" to listConnectionIDs().flatMap { listSessions(it).toList() }.size,
                    "consumerCount" to totalConsumerCount
            )
        }
    }

    @Test
    fun `rpc server close doesnt leak broker resources`() {
        rpcDriver {
            fun startAndCloseServer(broker: RpcBrokerHandle) {
                startRpcServerWithBrokerRunning(
                        configuration = RPCServerConfiguration.default.copy(consumerPoolSize = 1, producerPoolBound = 1),
                        ops = DummyOps,
                        brokerHandle = broker
                ).rpcServer.close()
            }

            val broker = startRpcBroker().get()
            startAndCloseServer(broker)
            val initial = broker.getStats()
            repeat(100) {
                startAndCloseServer(broker)
            }
            pollUntilTrue("broker resources to be released") {
                initial == broker.getStats()
            }
        }
    }

    @Test
    fun `rpc client close doesnt leak broker resources`() {
        rpcDriver {
            val server = startRpcServer(configuration = RPCServerConfiguration.default.copy(consumerPoolSize = 1, producerPoolBound = 1), ops = DummyOps).get()
            RPCClient<RPCOps>(server.broker.hostAndPort!!).start(RPCOps::class.java, rpcTestUser.username, rpcTestUser.password).close()
            val initial = server.broker.getStats()
            repeat(100) {
                val connection = RPCClient<RPCOps>(server.broker.hostAndPort!!).start(RPCOps::class.java, rpcTestUser.username, rpcTestUser.password)
                connection.close()
            }
            pollUntilTrue("broker resources to be released") {
                initial == server.broker.getStats()
            }
        }
    }

    @Test
    fun `rpc server close is idempotent`() {
        rpcDriver {
            val server = startRpcServer(ops = DummyOps).get()
            repeat(10) {
                server.rpcServer.close()
            }
        }
    }

    @Test
    fun `rpc client close is idempotent`() {
        rpcDriver {
            val serverShutdown = shutdownManager.follower()
            val server = startRpcServer(ops = DummyOps).get()
            serverShutdown.unfollow()
            // With the server up
            val connection1 = RPCClient<RPCOps>(server.broker.hostAndPort!!).start(RPCOps::class.java, rpcTestUser.username, rpcTestUser.password)
            repeat(10) {
                connection1.close()
            }
            val connection2 = RPCClient<RPCOps>(server.broker.hostAndPort!!).start(RPCOps::class.java, rpcTestUser.username, rpcTestUser.password)
            serverShutdown.shutdown()
            // With the server down
            repeat(10) {
                connection2.close()
            }
        }
    }

    interface LeakObservableOps: RPCOps {
        fun leakObservable(): Observable<Nothing>
    }

    @Test
    fun `client cleans up leaked observables`() {
        rpcDriver {
            val leakObservableOpsImpl = object : LeakObservableOps {
                val leakedUnsubscribedCount = AtomicInteger(0)
                override val protocolVersion = 0
                override fun leakObservable(): Observable<Nothing> {
                    return PublishSubject.create<Nothing>().doOnUnsubscribe {
                        leakedUnsubscribedCount.incrementAndGet()
                    }
                }
            }
            val server = startRpcServer<LeakObservableOps>(ops = leakObservableOpsImpl)
            val proxy = startRpcClient<LeakObservableOps>(server.get().broker.hostAndPort!!).get()
            // Leak many observables
            val N = 200
            (1..N).toList().parallelStream().forEach {
                proxy.leakObservable()
            }
            // In a loop force GC and check whether the server is notified
            while (true) {
                System.gc()
                if (leakObservableOpsImpl.leakedUnsubscribedCount.get() == N) break
                Thread.sleep(100)
            }
        }
    }

    interface ReconnectOps : RPCOps {
        fun ping(): String
    }

    @Test
    fun `client reconnects to rebooted server`() {
        // Artemis 2.1.0 has a bug that makes this test fail, and 25 trials are needed to make it fail reliably.
        // In the success case 25 trials take 2 minutes, so I've disabled them for the known-good Artemis version.
        // TODO: Remove multiple trials when we fix the Artemis bug (which should have its own test(s)).
        val trials = if (ArtemisConstants::class.java.`package`.implementationVersion == "1.5.3") 1 else 25
        rpcDriver {
            val coreBurner = thread {
                while (!Thread.interrupted()) {
                    // Spin.
                }
            }
            try {
                val ops = object : ReconnectOps {
                    override val protocolVersion = 0
                    override fun ping() = "pong"
                }
                var serverFollower = shutdownManager.follower()
                val serverPort = startRpcServer<ReconnectOps>(ops = ops).getOrThrow().broker.hostAndPort!!
                serverFollower.unfollow()
                val clientFollower = shutdownManager.follower()
                val client = startRpcClient<ReconnectOps>(serverPort).getOrThrow()
                clientFollower.unfollow()
                assertEquals("pong", client.ping())
                val background = Executors.newSingleThreadExecutor()
                (1..trials).forEach {
                    System.err.println("Start trial $it of $trials.")
                    serverFollower.shutdown()
                    serverFollower = shutdownManager.follower()
                    startRpcServer<ReconnectOps>(ops = ops, customPort = serverPort).getOrThrow()
                    serverFollower.unfollow()
                    val stopwatch = Stopwatch.createStarted()
                    val pingFuture = background.submit(Callable {
                        client.ping() // Would also hang in foreground, we need it in background so we can timeout.
                    })
                    assertEquals("pong", pingFuture.getOrThrow(10.seconds))
                    System.err.println("Took ${stopwatch.elapsed(TimeUnit.MILLISECONDS)} millis.")
                }
                background.shutdown() // No point in the hanging case.
                clientFollower.shutdown() // Driver would do this after the current server, causing 'legit' failover hang.
            } finally {
                with(coreBurner) {
                    interrupt()
                    join()
                }
            }
        }
    }

    interface TrackSubscriberOps : RPCOps {
        fun subscribe(): Observable<Unit>
    }

    /**
     * In this test we create a number of out of process RPC clients that call [TrackSubscriberOps.subscribe] in a loop.
     */
    @Test
    fun `server cleans up queues after disconnected clients`() {
        rpcDriver {
            val trackSubscriberOpsImpl = object : TrackSubscriberOps {
                override val protocolVersion = 0
                val subscriberCount = AtomicInteger(0)
                val trackSubscriberCountObservable = UnicastSubject.create<Unit>().share().
                        doOnSubscribe { subscriberCount.incrementAndGet() }.
                        doOnUnsubscribe { subscriberCount.decrementAndGet() }
                override fun subscribe(): Observable<Unit> {
                    return trackSubscriberCountObservable
                }
            }
            val server = startRpcServer<TrackSubscriberOps>(
                    configuration = RPCServerConfiguration.default.copy(
                            reapInterval = 100.millis
                    ),
                    ops = trackSubscriberOpsImpl
            ).get()

            val numberOfClients = 4
            val clients = Futures.allAsList((1 .. numberOfClients).map {
                startRandomRpcClient<TrackSubscriberOps>(server.broker.hostAndPort!!)
            }).get()

            // Poll until all clients connect
            pollUntilClientNumber(server, numberOfClients)
            pollUntilTrue("number of times subscribe() has been called") { trackSubscriberOpsImpl.subscriberCount.get() >= 100 }.get()
            // Kill one client
            clients[0].destroyForcibly()
            pollUntilClientNumber(server, numberOfClients - 1)
            // Kill the rest
            (1 .. numberOfClients - 1).forEach {
                clients[it].destroyForcibly()
            }
            pollUntilClientNumber(server, 0)
            // Now poll until the server detects the disconnects and unsubscribes from all obserables.
            pollUntilTrue("number of times subscribe() has been called") { trackSubscriberOpsImpl.subscriberCount.get() == 0 }.get()
        }
    }

    interface SlowConsumerRPCOps : RPCOps {
        fun streamAtInterval(interval: Duration, size: Int): Observable<ByteArray>
    }
    class SlowConsumerRPCOpsImpl : SlowConsumerRPCOps {
        override val protocolVersion = 0

        override fun streamAtInterval(interval: Duration, size: Int): Observable<ByteArray> {
            val chunk = ByteArray(size)
            return Observable.interval(interval.toMillis(), TimeUnit.MILLISECONDS).map { chunk }
        }
    }
    val dummyObservableSerialiser = object : Serializer<Observable<Any>>() {
        override fun write(kryo: Kryo?, output: Output?, `object`: Observable<Any>?) {
        }
        override fun read(kryo: Kryo?, input: Input?, type: Class<Observable<Any>>?): Observable<Any> {
            return Observable.empty()
        }
    }
    @Test
    fun `slow consumers are kicked`() {
        val kryoPool = KryoPool.Builder { RPCKryo(dummyObservableSerialiser) }.build()
        rpcDriver {
            val server = startRpcServer(maxBufferedBytesPerClient = 10 * 1024 * 1024, ops = SlowConsumerRPCOpsImpl()).get()

            // Construct an RPC session manually so that we can hang in the message handler
            val myQueue = "${RPCApi.RPC_CLIENT_QUEUE_NAME_PREFIX}.test.${random63BitValue()}"
            val session = startArtemisSession(server.broker.hostAndPort!!)
            session.createTemporaryQueue(myQueue, myQueue)
            val consumer = session.createConsumer(myQueue, null, -1, -1, false)
            consumer.setMessageHandler {
                Thread.sleep(50) // 5x slower than the server producer
                it.acknowledge()
            }
            val producer = session.createProducer(RPCApi.RPC_SERVER_QUEUE_NAME)
            session.start()

            pollUntilClientNumber(server, 1)

            val message = session.createMessage(false)
            val request = RPCApi.ClientToServer.RpcRequest(
                    clientAddress = SimpleString(myQueue),
                    id = RPCApi.RpcRequestId(random63BitValue()),
                    methodName = SlowConsumerRPCOps::streamAtInterval.name,
                    arguments = listOf(10.millis, 123456)
            )
            request.writeToClientMessage(kryoPool, message)
            producer.send(message)
            session.commit()

            // We are consuming slower than the server is producing, so we should be kicked after a while
            pollUntilClientNumber(server, 0)
        }
    }

}

fun RPCDriverExposedDSLInterface.pollUntilClientNumber(server: RpcServerHandle, expected: Int) {
    pollUntilTrue("number of RPC clients to become $expected") {
        val clientAddresses = server.broker.serverControl.addressNames.filter { it.startsWith(RPCApi.RPC_CLIENT_QUEUE_NAME_PREFIX) }
        clientAddresses.size == expected
    }.get()
}