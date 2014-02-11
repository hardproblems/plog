package com.airbnb.plog;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import kafka.javaapi.producer.Producer;
import kafka.producer.ProducerConfig;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.util.Properties;

@Slf4j
public class App {
    private final static String METADATA_BROKER_LIST = "metadata.broker.list";
    private final static String SERIALIZER_CLASS = "serializer.class";
    private final static String CLIENT_ID = "client.id";

    public static void main(String[] args) throws IOException, InterruptedException {
        Properties systemProperties = System.getProperties();
        if (systemProperties.getProperty(SERIALIZER_CLASS) == null)
            systemProperties.setProperty(SERIALIZER_CLASS, "kafka.serializer.StringEncoder");
        if (systemProperties.getProperty(METADATA_BROKER_LIST) == null)
            systemProperties.setProperty(METADATA_BROKER_LIST, "127.0.0.1:9092");
        if (systemProperties.getProperty(CLIENT_ID) == null)
            systemProperties.setProperty(CLIENT_ID, "plog_" + InetAddress.getLocalHost().getHostName());

        final Config config = ConfigFactory.load();
        new App().run(systemProperties, config);
    }

    private void run(Properties properties, Config config) {

        final Config plogConfig = config.getConfig("plog");
        final SimpleStatisticsReporter stats = new SimpleStatisticsReporter(properties.getProperty(CLIENT_ID));
        final Producer<String, String> producer = new Producer<String, String>(new ProducerConfig(properties));
        final Charset charset = Charset.forName(plogConfig.getString("charset"));
        final KafkaForwarder forwarder = new KafkaForwarder(
                plogConfig.getString("topic"),
                producer,
                stats,
                charset);
        final int maxLineLength = plogConfig.getInt("max_line_length");
        final int port = plogConfig.getInt("port");
        final PlogPDecoder protocolDecoder = new PlogPDecoder(stats);
        final PlogDefragmenter defragmenter = new PlogDefragmenter(stats,
                plogConfig.getInt("defrag.max_size"));
        stats.withDefragCacheStats(defragmenter.getCacheStats());
        final PlogCommandHandler commandHandler = new PlogCommandHandler(stats, config);

        final EventLoopGroup group = new NioEventLoopGroup();

        final ChannelFutureListener futureListener = new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture channelFuture) throws Exception {
                if (channelFuture.isDone() && !channelFuture.isSuccess()) {
                    log.error("Channel failure", channelFuture.cause());
                    System.exit(1);
                }
            }
        };

        new ServerBootstrap().group(group).channel(NioServerSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_REUSEADDR, true)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.SO_LINGER, 0)
                .handler(new LoggingHandler(LogLevel.WARN))
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) throws Exception {
                        channel.pipeline()
                                .addLast(new LineBasedFrameDecoder(maxLineLength))
                                .addLast(new Message.ByteBufToMessageDecoder())
                                .addLast(forwarder);
                    }
                }).bind(new InetSocketAddress(port)).addListener(futureListener);

        new Bootstrap().group(group).channel(NioDatagramChannel.class)
                .option(ChannelOption.SO_REUSEADDR, true)
                .option(ChannelOption.SO_RCVBUF, plogConfig.getInt("udp.SO_RCVBUF"))
                .option(ChannelOption.SO_SNDBUF, plogConfig.getInt("udp.SO_SNDBUF"))
                .handler(new ChannelInitializer<NioDatagramChannel>() {
                    @Override
                    protected void initChannel(NioDatagramChannel channel) throws Exception {
                        channel.pipeline()
                                .addLast(protocolDecoder)
                                .addLast(defragmenter)
                                .addLast(commandHandler)
                                .addLast(forwarder)
                                .addLast(new SimpleChannelInboundHandler<Void>() {
                                    @Override
                                    protected void channelRead0(ChannelHandlerContext ctx, Void msg) throws Exception {
                                        log.error("Some seriously weird stuff going on here!");
                                    }

                                    @Override
                                    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
                                        log.error("Exception down the UDP pipeline", cause);
                                        stats.exception();
                                    }
                                });
                    }
                }).bind(new InetSocketAddress(port)).addListener(futureListener);

        log.info("Started");
    }
}
