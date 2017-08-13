package net.neoremind.kraps

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{CountDownLatch, TimeUnit}

import net.neoremind.kraps.rpc._
import net.neoremind.kraps.rpc.netty.NettyRpcEnvFactory
import org.scalatest.{BeforeAndAfter, FlatSpec, Matchers}
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}
import scala.util.Try

/**
  * Created by xu.zhang on 7/30/17.
  */
abstract class BaseRpcTest extends FlatSpec with BeforeAndAfter with Matchers {

  var serverRpcEnv: RpcEnv = _

  implicit val log = LoggerFactory.getLogger(this.getClass)

  val _host = "localhost"

  val _port = new AtomicInteger(52345)

  after {
    if (serverRpcEnv != null) {
      serverRpcEnv.shutdown()
    }
    _port.incrementAndGet()
    log.info("======================================")
    Thread.sleep(200)
  }

  val startedCountDownLatch = new CountDownLatch(1)

  /**
    * when server starts and a count down latch `startedCountDownLatch` blocks client from calling,
    * this is the client blocking timeout
    */
  val serverStartTimeoutInMs = 5000

  /**
    * Client call wait unit that time, then assertion failure would occur
    */
  val clientCallWaitTimeInSec = "30s"

  def runServerAndAwaitTermination(block: => Unit,
                                   rpcConf: RpcConf = new RpcConf(),
                                   host: String = "localhost",
                                   port: Int = _port.get()) = {
    val future = Future {
      val config = RpcEnvServerConfig(rpcConf, "hello-server", host, port)
      serverRpcEnv = NettyRpcEnvFactory.create(config)
      block
      startedCountDownLatch.countDown()
      serverRpcEnv.awaitTermination()
    }
    future.onComplete {
      case scala.util.Success(value) => log.info(s"Shut down server on host=$host, port=" + port)
      case scala.util.Failure(e) => log.error(e.getMessage, e)
    }
  }

  def clientCall[T](endpointName: String)(runBlock: RpcEndpointRef => Future[T],
                                          assertBlock: PartialFunction[Try[T], Unit],
                                          rpcConf: RpcConf = new RpcConf(),
                                          host: String = _host,
                                          port: Int = _port.get()) = {
    var rpcEnv: RpcEnv = null
    try {
      startedCountDownLatch.await(serverStartTimeoutInMs, TimeUnit.MILLISECONDS)
      Thread.sleep(100)
      val config = RpcEnvClientConfig(rpcConf, "test-client")
      rpcEnv = NettyRpcEnvFactory.create(config)
      val endPointRef: RpcEndpointRef = rpcEnv.setupEndpointRef(RpcAddress(host, port), endpointName)
      log.info(s"created $endPointRef")
      val future = runBlock(endPointRef)
      future.onComplete(assertBlock)
      Await.result(future, Duration.apply(clientCallWaitTimeInSec))
    } catch {
      case e: RuntimeException => {
        log.error(e.getMessage, e)
        throw e
      }
    } finally {
      if (rpcEnv != null) rpcEnv.shutdown()
    }
  }

  def clientCallNonFuture[T](endpointName: String)(runBlock: RpcEndpointRef => T,
                                                   assertBlock: PartialFunction[Try[T], Unit],
                                                   rpcConf: RpcConf = new RpcConf(),
                                                   host: String = _host,
                                                   port: Int = _port.get()) = {
    var rpcEnv: RpcEnv = null
    try {
      startedCountDownLatch.await(serverStartTimeoutInMs, TimeUnit.MILLISECONDS)
      val config = RpcEnvClientConfig(rpcConf, "test-client")
      rpcEnv = NettyRpcEnvFactory.create(config)
      val endPointRef: RpcEndpointRef = rpcEnv.setupEndpointRef(RpcAddress(host, port), endpointName)
      log.info(s"created $endPointRef")
      runBlock(endPointRef)
    } catch {
      case e: RuntimeException => {
        log.error(e.getMessage, e)
        throw e
      }
    } finally {
      if (rpcEnv != null) rpcEnv.shutdown()
    }
  }
}