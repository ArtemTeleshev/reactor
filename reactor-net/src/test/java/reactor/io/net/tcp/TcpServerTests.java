/*
 * Copyright (c) 2011-2015 Pivotal Software Inc., Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.io.net.tcp;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufProcessor;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.http.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;
import org.zeromq.ZMsg;
import reactor.Environment;
import reactor.core.dispatch.SynchronousDispatcher;
import reactor.core.support.UUIDUtils;
import reactor.fn.Consumer;
import reactor.fn.Supplier;
import reactor.io.buffer.Buffer;
import reactor.io.codec.*;
import reactor.io.codec.json.JsonCodec;
import reactor.io.net.NetChannelStream;
import reactor.io.net.NetStreams;
import reactor.io.net.config.ServerSocketOptions;
import reactor.io.net.config.SslOptions;
import reactor.io.net.netty.NettyServerSocketOptions;
import reactor.io.net.netty.tcp.NettyTcpClient;
import reactor.io.net.netty.tcp.NettyTcpServer;
import reactor.io.net.tcp.spec.TcpClientSpec;
import reactor.io.net.tcp.spec.TcpServerSpec;
import reactor.io.net.tcp.support.SocketUtils;
import reactor.io.net.zmq.tcp.ZeroMQTcpServer;

import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * @author Jon Brisbin
 * @author Stephane Maldini
 */
public class TcpServerTests {

	final Logger          log        = LoggerFactory.getLogger(TcpServerTests.class);
	final ExecutorService threadPool = Executors.newCachedThreadPool();
	final int             msgs       = 150;
	final int             threads    = 4;

	Environment    env;
	CountDownLatch latch;
	AtomicLong count = new AtomicLong();
	AtomicLong start = new AtomicLong();
	AtomicLong end   = new AtomicLong();

	@Before
	public void loadEnv() {
		env = new Environment();
		latch = new CountDownLatch(msgs * threads);
	}

	@After
	public void cleanup() {
		threadPool.shutdownNow();
	}

	@Test
	public void tcpServerHandlesJsonPojosOverSsl() throws InterruptedException {
		final int port = SocketUtils.findAvailableTcpPort();
		SslOptions serverOpts = new SslOptions()
				.keystoreFile("./src/test/resources/server.jks")
				.keystorePasswd("changeit");

		SslOptions clientOpts = new SslOptions()
				.keystoreFile("./src/test/resources/client.jks")
				.keystorePasswd("changeit")
				.trustManagers(new Supplier<TrustManager[]>() {
					@Override
					public TrustManager[] get() {
						return new TrustManager[]{
								new X509TrustManager() {
									@Override
									public void checkClientTrusted(X509Certificate[] x509Certificates, String s)
											throws CertificateException {
										// trust all
									}

									@Override
									public void checkServerTrusted(X509Certificate[] x509Certificates, String s)
											throws CertificateException {
										// trust all
									}

									@Override
									public X509Certificate[] getAcceptedIssuers() {
										return new X509Certificate[0];
									}
								}
						};
					}
				});

		JsonCodec<Pojo, Pojo> codec = new JsonCodec<Pojo, Pojo>(Pojo.class);

		final CountDownLatch latch = new CountDownLatch(1);
		final TcpClient<Pojo, Pojo> client = NetStreams.tcpClient(s ->
						s.env(env)
								.ssl(clientOpts)
								.codec(codec)
								.connect("localhost", port)
		);

		final TcpServer<Pojo, Pojo> server = NetStreams.tcpServer(s ->
						s
								.env(env)
								.ssl(serverOpts)
								.listen("localhost", port)
								.codec(codec)
		);

		server.consume(new Consumer<NetChannelStream<Pojo, Pojo>>() {
			@Override
			public void accept(NetChannelStream<Pojo, Pojo> ch) {
				ch.consume(new Consumer<Pojo>() {
					@Override
					public void accept(Pojo data) {
						if ("John Doe".equals(data.getName())) {
							latch.countDown();
						}
					}
				});
			}
		});

		server.start().await();
		client.open().await().send(new Pojo("John Doe"));

		assertTrue("Latch was counted down", latch.await(5, TimeUnit.SECONDS));

		client.close().await();
		server.shutdown().await();
	}

	@Test
	public void tcpServerHandlesLengthFieldData() throws InterruptedException {
		final int port = SocketUtils.findAvailableTcpPort();

		TcpServer<byte[], byte[]> server = NetStreams.tcpServer(s ->
				s.env(env)
				.synchronousDispatcher()
				.options(new ServerSocketOptions()
						.backlog(1000)
						.reuseAddr(true)
						.tcpNoDelay(true))
				.listen(port)
				.codec(new LengthFieldCodec<byte[], byte[]>(StandardCodecs.BYTE_ARRAY_CODEC))
		);

		server.consume(new Consumer<NetChannelStream<byte[], byte[]>>() {
			@Override
			public void accept(NetChannelStream<byte[], byte[]> ch) {
				ch.consume(new Consumer<byte[]>() {
					long num = 1;

					@Override
					public void accept(byte[] bytes) {
						latch.countDown();
						ByteBuffer bb = ByteBuffer.wrap(bytes);
						if (bb.remaining() < 4) {
							System.err.println("insufficient len: " + bb.remaining());
						}
						int next = bb.getInt();
						if (next != num++) {
							System.err.println(this + " expecting: " + next + " but got: " + (num - 1));
						}
					}
				});
			}
		});

		server.start().await();

		start.set(System.currentTimeMillis());
		for (int i = 0; i < threads; i++) {
			threadPool.submit(new LengthFieldMessageWriter(port));
		}

		assertTrue("Latch was counted down", latch.await(25, TimeUnit.SECONDS));
		end.set(System.currentTimeMillis());

		double elapsed = (end.get() - start.get()) * 1.0;
		System.out.println("elapsed: " + (int) elapsed + "ms");
		System.out.println("throughput: " + (int) ((msgs * threads) / (elapsed / 1000)) + "/sec");

		server.shutdown().await();
	}

	@Test
	public void tcpServerHandlesFrameData() throws InterruptedException {
		final int port = SocketUtils.findAvailableTcpPort();

		TcpServer<Frame, Frame> server = new TcpServerSpec<Frame, Frame>(NettyTcpServer.class)
				.env(env)
				.synchronousDispatcher()
				.options(new ServerSocketOptions()
						.backlog(1000)
						.reuseAddr(true)
						.tcpNoDelay(true))
				.listen(port)
				.codec(new FrameCodec(2, FrameCodec.LengthField.SHORT))
				.get();

		server.consume(new Consumer<NetChannelStream<Frame, Frame>>() {
			@Override
			public void accept(NetChannelStream<Frame, Frame> ch) {
				ch.consume(new Consumer<Frame>() {
					@Override
					public void accept(Frame frame) {
						short prefix = frame.getPrefix().readShort();
						assertThat("prefix is 0", prefix == 0);
						Buffer data = frame.getData();
						assertThat("len is 128", data.remaining() == 128);

						latch.countDown();
					}
				});
			}
		});

		server.start().await();

		start.set(System.currentTimeMillis());
		for (int i = 0; i < threads; i++) {
			threadPool.submit(new FramedLengthFieldMessageWriter(port));
		}

		assertTrue("Latch was counted down", latch.await(60, TimeUnit.SECONDS));
		end.set(System.currentTimeMillis());

		double elapsed = (end.get() - start.get()) * 1.0;
		System.out.println("elapsed: " + (int) elapsed + "ms");
		System.out.println("throughput: " + (int) ((msgs * threads) / (elapsed / 1000)) + "/sec");

		server.shutdown().await();
	}

	@Test
	public void exposesRemoteAddress() throws InterruptedException {
		final int port = SocketUtils.findAvailableTcpPort();
		final CountDownLatch latch = new CountDownLatch(1);

		TcpClient<Buffer, Buffer> client = new TcpClientSpec<Buffer, Buffer>(NettyTcpClient.class)
				.env(env)
				.synchronousDispatcher()
				.connect("localhost", port)
				.get();

		TcpServer<Buffer, Buffer> server = new TcpServerSpec<Buffer, Buffer>(NettyTcpServer.class)
				.env(env)
				.synchronousDispatcher()
				.listen(port)
				.codec(new PassThroughCodec<Buffer>())
				.get();

		server.consume(new Consumer<NetChannelStream<Buffer, Buffer>>() {
			@Override
			public void accept(NetChannelStream<Buffer, Buffer> ch) {
				InetSocketAddress remoteAddr = ch.remoteAddress();
				assertNotNull("remote address is not null", remoteAddr.getAddress());
				latch.countDown();
			}
		});

		server.start().await();

		NetChannelStream<Buffer, Buffer> out = client.open().await();
		out.send(Buffer.wrap("Hello World!"));

		assertTrue("latch was counted down", latch.await(5, TimeUnit.SECONDS));

		server.shutdown().await();
	}

	@Test
	public void exposesNettyPipelineConfiguration() throws InterruptedException {
		final int port = SocketUtils.findAvailableTcpPort();
		final CountDownLatch latch = new CountDownLatch(2);

		final TcpClient<String, String> client = new TcpClientSpec<String, String>(NettyTcpClient.class)
				.env(env)
				.connect("localhost", port)
				.codec(StandardCodecs.LINE_FEED_CODEC)
				.get();

		Consumer<NetChannelStream<String, String>> serverHandler = new Consumer<NetChannelStream<String, String>>() {
			@Override
			public void accept(NetChannelStream<String, String> ch) {
				ch.consume(new Consumer<String>() {
					@Override
					public void accept(String data) {
						log.info("data " + data + " on " + ch);
						latch.countDown();
					}
				});
			}
		};

		TcpServer<String, String> server = new TcpServerSpec<String, String>(NettyTcpServer.class)
				.env(env)
				.options(new NettyServerSocketOptions()
						.pipelineConfigurer(new Consumer<ChannelPipeline>() {
							@Override
							public void accept(ChannelPipeline pipeline) {
								pipeline.addLast(new LineBasedFrameDecoder(8 * 1024));
							}
						}))
				.listen(port)
				.codec(StandardCodecs.STRING_CODEC)
				.get();

		server.consume(serverHandler);
		server.start().await();

		client.open().await().echo("Hello World!").echo("Hello 11!");

		assertTrue("Latch was counted down", latch.await(5, TimeUnit.SECONDS));

		client.close().await();
		server.shutdown().await();
	}

	@Test
	public void exposesNettyByteBuf() throws InterruptedException {
		final int port = SocketUtils.findAvailableTcpPort();
		final CountDownLatch latch = new CountDownLatch(msgs);

		TcpServer<ByteBuf, ByteBuf> server = new TcpServerSpec<ByteBuf, ByteBuf>(NettyTcpServer.class)
				.env(env)
				.listen(port)
				.dispatcher(SynchronousDispatcher.INSTANCE)
				.get();

		server.consume(new Consumer<NetChannelStream<ByteBuf, ByteBuf>>() {
			@Override
			public void accept(NetChannelStream<ByteBuf, ByteBuf> ch) {
				ch.consume(new Consumer<ByteBuf>() {
					@Override
					public void accept(ByteBuf byteBuf) {
						byteBuf.forEachByte(new ByteBufProcessor() {
							@Override
							public boolean process(byte value) throws Exception {
								if (value == '\n') {
									latch.countDown();
								}
								return true;
							}
						});
						byteBuf.release();
					}
				});
			}
		});

		log.info("Starting raw server on tcp://localhost:{}", port);
		server.start().await();

		for (int i = 0; i < threads; i++) {
			threadPool.submit(new DataWriter(port));
		}

		try {
			assertTrue("Latch was counted down", latch.await(10, TimeUnit.SECONDS));
		} finally {
			server.shutdown().await();
		}

	}

	@Test
	public void exposesHttpServer() throws InterruptedException {
		final int port = SocketUtils.findAvailableTcpPort();

		final TcpServer<HttpRequest, HttpResponse> server
				= new TcpServerSpec<HttpRequest, HttpResponse>(NettyTcpServer.class)
				.env(env)
				.listen(port)
				.options(new NettyServerSocketOptions()
						.pipelineConfigurer(new Consumer<ChannelPipeline>() {
							@Override
							public void accept(ChannelPipeline pipeline) {
								pipeline.addLast(new HttpRequestDecoder());
								pipeline.addLast(new HttpObjectAggregator(Integer.MAX_VALUE));
								pipeline.addLast(new HttpResponseEncoder());
							}
						}))
				.get();

		server.consume(new Consumer<NetChannelStream<HttpRequest, HttpResponse>>() {
			@Override
			public void accept(final NetChannelStream<HttpRequest, HttpResponse> ch) {
				ch.in().consume(new Consumer<HttpRequest>() {
					@Override
					public void accept(HttpRequest req) {
						ByteBuf buf = Unpooled.copiedBuffer("Hello World!".getBytes());
						int len = buf.readableBytes();
						DefaultFullHttpResponse resp = new DefaultFullHttpResponse(
								HttpVersion.HTTP_1_1,
								HttpResponseStatus.OK,
								buf
						);
						resp.headers().set(HttpHeaders.Names.CONTENT_LENGTH, len);
						resp.headers().set(HttpHeaders.Names.CONTENT_TYPE, "text/plain");
						resp.headers().set(HttpHeaders.Names.CONNECTION, "Keep-Alive");

						ch.send(resp);

						if (req.getMethod() == HttpMethod.GET && "/test".equals(req.getUri())) {
							latch.countDown();
						}
					}
				});
			}
		});

		log.info("Starting HTTP server on http://localhost:{}/", port);
		server.start().await();

		for (int i = 0; i < threads; i++) {
			threadPool.submit(new HttpRequestWriter(port));
		}

		assertTrue("Latch was counted down", latch.await(15, TimeUnit.SECONDS));
		end.set(System.currentTimeMillis());

		double elapsed = (end.get() - start.get());
		System.out.println("HTTP elapsed: " + (int) elapsed + "ms");
		System.out.println("HTTP throughput: " + (int) ((msgs * threads) / (elapsed / 1000)) + "/sec");

		server.shutdown().await();
	}

	@Test(timeout = 60000)
	public void exposesZeroMQServer() throws InterruptedException {
		final int port = SocketUtils.findAvailableTcpPort();
		final CountDownLatch latch = new CountDownLatch(2);
		ZContext zmq = new ZContext();

		TcpServer<Buffer, Buffer> server = new TcpServerSpec<Buffer, Buffer>(ZeroMQTcpServer.class)
				.env(env)
				.listen("127.0.0.1", port)
				.get();

		server.consume(ch -> {
			ch.consume(buff -> {
				if (buff.remaining() == 128) {
					latch.countDown();
				} else {
					log.info("data: {}", buff.asString());
				}
				ch.echo(Buffer.wrap("Goodbye World!"));
			});
		});

		assertTrue("Server was started", server.start().awaitSuccess(5, TimeUnit.SECONDS));

		ZeroMQWriter zmqw = new ZeroMQWriter(zmq, port, latch);
		threadPool.submit(zmqw);

		assertTrue("reply was received", latch.await(5, TimeUnit.SECONDS));
		assertTrue("Server was stopped", server.shutdown().awaitSuccess(5, TimeUnit.SECONDS));

		//zmq.destroy();
	}

	public static class Pojo {
		private String name;

		private Pojo() {
		}

		private Pojo(String name) {
			this.name = name;
		}

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}

		@Override
		public String toString() {
			return "Pojo{" +
					"name='" + name + '\'' +
					'}';
		}
	}

	private class LengthFieldMessageWriter implements Runnable {
		private final Random rand = new Random();
		private final int port;
		private final int length;

		private LengthFieldMessageWriter(int port) {
			this.port = port;
			this.length = rand.nextInt(256);
		}

		@Override
		public void run() {
			try {
				java.nio.channels.SocketChannel ch = java.nio.channels.SocketChannel.open(new InetSocketAddress(port));

				System.out.println("writing " + msgs + " messages of " + length + " byte length...");

				int num = 1;
				start.set(System.currentTimeMillis());
				for (int j = 0; j < msgs; j++) {
					ByteBuffer buff = ByteBuffer.allocate(length + 4);
					buff.putInt(length);
					buff.putInt(num++);
					buff.position(0);
					buff.limit(length + 4);

					ch.write(buff);

					count.incrementAndGet();
				}
			} catch (IOException e) {
			}
		}
	}

	private class FramedLengthFieldMessageWriter implements Runnable {
		private final short length = 128;
		private final int port;

		private FramedLengthFieldMessageWriter(int port) {
			this.port = port;
		}

		@Override
		public void run() {
			try {
				java.nio.channels.SocketChannel ch = java.nio.channels.SocketChannel.open(new InetSocketAddress(port));

				System.out.println("writing " + msgs + " messages of " + length + " byte length...");

				start.set(System.currentTimeMillis());
				for (int j = 0; j < msgs; j++) {
					ByteBuffer buff = ByteBuffer.allocate(length + 4);
					buff.putShort((short) 0);
					buff.putShort(length);
					for (int i = 4; i < length; i++) {
						buff.put((byte) 1);
					}
					buff.flip();
					buff.limit(length + 4);

					ch.write(buff);

					count.incrementAndGet();
				}
				ch.close();
			} catch (IOException e) {
			}
		}
	}

	private class HttpRequestWriter implements Runnable {
		private final int port;

		private HttpRequestWriter(int port) {
			this.port = port;
		}

		@Override
		public void run() {
			try {
				java.nio.channels.SocketChannel ch = java.nio.channels.SocketChannel.open(new InetSocketAddress(port));
				start.set(System.currentTimeMillis());
				for (int i = 0; i < msgs; i++) {
					ch.write(Buffer.wrap("GET /test HTTP/1.1\r\nConnection: Close\r\n\r\n").byteBuffer());
					ByteBuffer buff = ByteBuffer.allocate(4 * 1024);
					ch.read(buff);
				}
				ch.close();
			} catch (IOException e) {
			}
		}
	}

	private class DataWriter implements Runnable {
		private final int port;

		private DataWriter(int port) {
			this.port = port;
		}

		@Override
		public void run() {
			try {
				java.nio.channels.SocketChannel ch = java.nio.channels.SocketChannel.open(new InetSocketAddress(port));
				start.set(System.currentTimeMillis());
				for (int i = 0; i < msgs; i++) {
					ch.write(Buffer.wrap("Hello World!\n").byteBuffer());
				}
				ch.close();
			} catch (IOException e) {
			}
		}
	}

	private class ZeroMQWriter implements Runnable {
		private final Random random = new Random();
		private final ZContext       zmq;
		private final int            port;
		private final CountDownLatch latch;

		private ZeroMQWriter(ZContext zmq, int port, CountDownLatch latch) {
			this.zmq = zmq;
			this.port = port;
			this.latch = latch;
		}

		@Override
		public void run() {
			String id = UUIDUtils.random().toString();
			ZMQ.Socket socket = zmq.createSocket(ZMQ.DEALER);
			socket.setIdentity(id.getBytes());
			socket.connect("tcp://127.0.0.1:" + port);

			byte[] data = new byte[128];
			random.nextBytes(data);

			socket.send(data);

			ZMsg reply = ZMsg.recvMsg(socket);
			log.info("reply: {}", reply);
			latch.countDown();

			//zmq.destroySocket(socket);
		}
	}

}
