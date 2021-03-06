package org.switcher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.switcher.exception.ConnectionAlreadySetupException;
import org.switcher.exception.ConnectionNotFoundException;

import java.net.InetSocketAddress;
import java.text.MessageFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

import static org.switcher.exception.SwitcherException.UNEXPECTED_EXCEPTION;

/**
 * 管理连接，根据给定的socket，能返回其详细信息
 */
public class ConnectionManager {
    private final static Logger logger = LoggerFactory.getLogger(ConnectionManager.class);

    /**
     * 连接映射，方便通过 {@link InetSocketAddress} 来查找其详细信息
     */
    final Map<InetSocketAddress, ConnectionDetail> connections;

    private final Switcher switcher;

    ConnectionManager(Switcher switcher) {
        this.switcher = switcher;
        connections = new ConcurrentHashMap<>();
    }

    public static String connectionChain(ConnectionPair connectionPair) {
        return connectionChain(connectionPair.clientSocket, connectionPair.connectionDetail.proxySocket,
                connectionPair.connectionDetail.uri);
    }

    /**
     * 代理链
     *
     * @param clientSocket 客户端的socket
     * @param proxySocket  上游代理的socket
     * @param uri          目标uri
     * @return 格式化后的代理链
     */
    public static String connectionChain(InetSocketAddress clientSocket, InetSocketAddress proxySocket, String uri) {
        String proxyType = proxySocket == UpstreamProxyManager.DIRECT_CONNECTION ? "直接连接" : "代理连接";
        return MessageFormat.format("{0} {1} == {2} => {3} ", proxyType, clientSocket, proxySocket, uri);
    }

    /**
     * 增加新连接
     *
     * @param clientSocket 客户端的socket
     * @param proxySocket  上游代理的socket
     * @param uri          目标uri
     */
    void add(InetSocketAddress clientSocket, InetSocketAddress proxySocket, String uri) {
        UpstreamProxyDetail upstreamProxyDetail = switcher.upstreamProxyManager.sureGetDetail(proxySocket);
        SpeedRecorder parent = null;
        if (upstreamProxyDetail != null) {
            // 由于upstreamProxy可能在被获取后又恰好被释放，因此需要获取状态锁
            upstreamProxyDetail.stateLock.readLock().lock();
            parent = upstreamProxyDetail.speedRecorder;
        }
        SpeedRecorder finalParent = parent;

        // 用AtomicBoolean并不是为了原子性，可以用new boolean[]{true}来代替
        AtomicBoolean contains = new AtomicBoolean(true);

        // 需要原子性操作，不能换为containsKey+put
        ConnectionDetail connectionDetail = connections.computeIfAbsent(clientSocket, __ -> {
            // 此处如果修改boolean则会报错，所以才需要用引用的方式
            contains.set(false);
            return new ConnectionDetail(proxySocket, uri, finalParent);
        });

        if (upstreamProxyDetail != null) {
            if (!contains.get()) {
                upstreamProxyDetail.relevantConnections.add(clientSocket);
            }
            if (upstreamProxyDetail.removed) {
                // 如果upstreamProxy恰好被移除了，那么需要中止这一个连接
                connectionDetail.abort = true;
            }
            // 释放锁
            upstreamProxyDetail.stateLock.readLock().unlock();
        } else {
            connectionDetail.abort = true;
        }

        // 判断是否已经存在该键，如果存在则输出日志并抛出错误，否则打印一条info
        if (contains.get()) {
            String newConnectionChain = connectionChain(clientSocket, proxySocket, uri);
            String existingConnectionChain = connectionChain(clientSocket,
                    connectionDetail.proxySocket, connectionDetail.uri);
            logger.debug("尝试增加{}到 {}，但在 {} 中，已存在{}", newConnectionChain, this,
                    this, existingConnectionChain, new ConnectionAlreadySetupException());
        } else {
            logger.info("新增{}", connectionChain(clientSocket, proxySocket, uri));
        }
    }

    public int size() {
        return connections.size();
    }

    /**
     * 获取所有连接
     *
     * @return 所有连接
     */
    public List<ConnectionPair> getAll() {
        List<ConnectionPair> connectionPairs = new ArrayList<>(connections.size());
        connections.forEach((clientSocket, connectionDetail) ->
                connectionPairs.add(new ConnectionPair(clientSocket, connectionDetail)));
        return connectionPairs;
    }

    public void forEach(BiConsumer<InetSocketAddress, ConnectionDetail> action) {
        connections.forEach(action);
    }

    /**
     * 获取连接详细信息
     *
     * @param clientSocket 客户端的socket
     * @return 连接的详细信息
     */
    public ConnectionDetail getDetail(InetSocketAddress clientSocket) {
        return connections.get(clientSocket);
    }

    ConnectionDetail sureGetDetail(InetSocketAddress clientSocket) {
        ConnectionDetail connectionDetail = getDetail(clientSocket);
        if (connectionDetail == null) {
            logger.debug(UNEXPECTED_EXCEPTION, new ConnectionNotFoundException());
        }
        return connectionDetail;
    }

    /**
     * 移除连接（断开连接时进行处理）
     *
     * @param clientSocket 客户端的socket
     */
    void remove(InetSocketAddress clientSocket) {
        ConnectionDetail connectionDetail = connections.remove(clientSocket);
        if (connectionDetail == null) {
            logger.debug(UNEXPECTED_EXCEPTION, new ConnectionNotFoundException());
        } else {
            connectionDetail.speedRecorder.tearDown();
            UpstreamProxyDetail upstreamProxyDetail = switcher.upstreamProxyManager
                    .sureGetDetail(connectionDetail.proxySocket);
            if (upstreamProxyDetail != null) {
                upstreamProxyDetail.relevantConnections.remove(clientSocket);
            }
            logger.info("移除连接 {}", clientSocket);
        }
    }

    private void abort0(InetSocketAddress clientSocket, boolean sure) {
        logger.info("中止连接 {}", clientSocket);
        ConnectionDetail connectionDetail = sure ? sureGetDetail(clientSocket) : getDetail(clientSocket);
        if (connectionDetail != null) {
            connectionDetail.abort = true;
        }
    }

    void sureAbort(InetSocketAddress clientSocket) {
        abort0(clientSocket, true);
    }

    /**
     * 中止连接
     *
     * @param clientSocket 客户端的socket
     */
    public void abort(InetSocketAddress clientSocket) {
        abort0(clientSocket, false);
    }
}
