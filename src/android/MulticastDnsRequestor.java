package com.koalasafe.cordova.plugin.multicastdns;

import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Enumeration;

/**
 * Created by steve on 16/07/15.
 */
public class MulticastDnsRequestor {

    private static final String TAG = "MulticastDnsRequestor";
    private static final int BUFFER_SIZE = 4096;
    // 以下は未使用の旧コンストラクタ（引数付き）専用のフィールド。
    // 唯一使われている1引数コンストラクタ(Context)ではこれらを初期化しないため、
    // final にすると全コンストラクタ経路での初期化が要求されコンパイルエラーになる。
    private int queryTimeout;
    private Boolean isIPv6;
    private String logPrefix;
    private String multicastIP;
    private int port;
    private Context context;

    private static final long RETRY_INTERVAL = 1500; // 1.5秒再送
    private static final int MAX_QUERY_COUNT = 7; // 送信回数（初回送信＋再送6回。1回につきQU/QMの2パケット）
    private static final int QUERY_TIMEOUT = 10000; // 全体のクエリタイムアウト（10秒）
    private static final int SOCKET_RECEIVE_TIMEOUT = 5000; // ソケット受信タイムアウトの上限（ミリ秒）
    private static final int MIN_SOCKET_RECEIVE_TIMEOUT = 1; // setSoTimeout(0)は無制限待ちになるため下限を設ける
    private static final int MULTICAST_TTL = 255;
    private static final String MULTICAST_IP = "224.0.0.251";
    private static final int MULTICAST_PORT = 5353;
    private static final String MULTICAST_LOCK_TAG = "MulticastDNSRequestor";
    private static final String LOG_PREFIX = "[mDNS] ";
    private static final String LOCAL_SUFFIX = ".local";
    private static final String LOCAL_SUFFIX_REGEX = "\\.local$";
    private static final String ERRNO_EPERM = "EPERM";
    private static final String INET_ADDRESS_PREFIX = "/";
    private static final int IPV4_ADDRESS_BYTE_LENGTH = 4;
    private static final int NO_WIFI_IP = 0;

    private InetAddress multicastIPAddr;
    private WifiManager wifiManager;
    private NetworkInterface networkInterface;

    public MulticastDnsRequestor(Context context) {
        try {
            this.multicastIPAddr = InetAddress.getByName(MULTICAST_IP);
            if (context != null) {
                this.wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
                this.networkInterface = getWifiNetworkInterface();
            }
        } catch (Exception e) {
            Log.e(TAG, LOG_PREFIX + "Initialization error in constructor", e);
        }
    }

    public MulticastDnsRequestor(WifiManager wifiManager) throws UnknownHostException, SocketException {
        // Common multicast ip and port for service discovery
        this(MULTICAST_IP, MULTICAST_PORT, wifiManager);
    }

    public MulticastDnsRequestor(String multicastIP, int port, WifiManager wifiManager) throws UnknownHostException, SocketException {
        this(multicastIP, port, null, wifiManager);
    }

    public MulticastDnsRequestor(String multicastIP, int port, Context context) throws UnknownHostException, SocketException {
        this(multicastIP, port, null, (WifiManager)context.getSystemService(Context.WIFI_SERVICE));
        this.context = context; //make sure we live long enough...
    }

    public MulticastDnsRequestor(String multicastIP, int port, NetworkInterface networkInterface, WifiManager wifiManager) throws UnknownHostException, SocketException {
        this.multicastIP = multicastIP;
        this.multicastIPAddr = InetAddress.getByName(this.multicastIP);
        this.isIPv6 = this.multicastIPAddr instanceof Inet6Address;
        this.logPrefix = this.isIPv6 ? "ipv6: " : "ipv4: ";
        this.port = port;
        this.queryTimeout = 10000; //Milliseconds

        Log.d(TAG, String.format("Using %s:%s", multicastIPAddr, port));

        this.wifiManager = wifiManager;

        if (networkInterface == null) {
            this.networkInterface = getWifiNetworkInterface();
        } else {
            this.networkInterface = networkInterface;
        }
        if (this.networkInterface == null) {
            throw new SocketException("Could not locate wifi network interface.");
        }
        if (!this.networkInterface.supportsMulticast()) {
            Log.e(TAG, "networkInterface does not support multicast");
        }
    }


    /**
     * Send a multicast DNS request for the host, then listen until a response for the query is received
     *
     * @param host
     * @return
     * @throws IOException
     */
    public String query(String host) throws IOException, QueryTimeoutException {
        if (this.wifiManager == null) {
            throw new IOException("WifiManager is not initialized.");
        }
        if (this.networkInterface == null) {
            throw new IOException("NetworkInterface is not initialized.");
        }

        // 渡されたホスト名をすべて小文字にし、末尾のドットを削る
        String hostLower = (host == null) ? "" : host.toLowerCase();
        if (hostLower.endsWith(".")) {
            hostLower = hostLower.substring(0, hostLower.length() - 1);
        }

        // プリンターへ送信するための「mDNS形式（小文字+.local）」の文字列を作る
        String sendHost = hostLower;
        if (!sendHost.endsWith(LOCAL_SUFFIX)) {
            sendHost += LOCAL_SUFFIX;
        }

        // 受信パケットとマッチングさせるためのバリエーション
        String hostWithLocal = hostLower.endsWith(LOCAL_SUFFIX) ? hostLower : hostLower + LOCAL_SUFFIX;
        String hostWithoutLocal = hostLower.endsWith(LOCAL_SUFFIX) ? hostLower.replaceFirst(LOCAL_SUFFIX_REGEX, "") : hostLower;

        WifiManager.MulticastLock multicastLock = this.wifiManager.createMulticastLock(MULTICAST_LOCK_TAG);
        MulticastSocket socket = null;

        try {
            multicastLock.acquire();

            // ===========================
            // ソケットをここで全部作る
            // ===========================
            socket = new MulticastSocket(MULTICAST_PORT);
            socket.setReuseAddress(true);
            socket.setNetworkInterface(this.networkInterface);
            socket.joinGroup(new java.net.InetSocketAddress(this.multicastIPAddr, MULTICAST_PORT), this.networkInterface);
            socket.setTimeToLive(MULTICAST_TTL);
            socket.setSoTimeout(SOCKET_RECEIVE_TIMEOUT);

            byte[] responseBuffer = new byte[BUFFER_SIZE];
            DatagramPacket response = new DatagramPacket(responseBuffer, BUFFER_SIZE);

            DNSMessage[] queries = new DNSMessage[] {
                    new DNSMessage(sendHost, true), // i = 0: QU
                    new DNSMessage(sendHost, false) // i = 1: QM
            };

            long startTime = System.currentTimeMillis();
            long lastSentTime = startTime - RETRY_INTERVAL; // 初回即時送信のため
            int sentCount = 0; // 送信済み回数（MAX_QUERY_COUNT に達したら再送しない）

            while (System.currentTimeMillis() - startTime < QUERY_TIMEOUT) {
                long now = System.currentTimeMillis();

                // --- 定期送信判定 ---
                if (sentCount < MAX_QUERY_COUNT && now - lastSentTime >= RETRY_INTERVAL) {
                    lastSentTime = now;
                    sentCount++;

                    for (DNSMessage qmsg : queries) {
                        byte[] queryBytes = qmsg.serialize();
                        DatagramPacket request = new DatagramPacket(
                                queryBytes,
                                queryBytes.length,
                                multicastIPAddr,
                                MULTICAST_PORT);

                        try {
                            socket.send(request);
                        } catch (IOException e) {

                            long timeLeft = QUERY_TIMEOUT - (System.currentTimeMillis() - startTime);

                            if (e.getMessage() != null &&
                                    e.getMessage().contains(ERRNO_EPERM) &&
                                    timeLeft > 0) {
                                break;
                            }

                            throw e;
                        }
                    }
                }

                // --- 受信待ち時間の設定 ---
                long nextSendIn = (sentCount < MAX_QUERY_COUNT)
                        ? RETRY_INTERVAL - (System.currentTimeMillis() - lastSentTime)
                        : Long.MAX_VALUE;
                long timeLeftForQuery = QUERY_TIMEOUT - (System.currentTimeMillis() - startTime);
                long waitMillis = Math.min(nextSendIn, timeLeftForQuery);
                waitMillis = Math.min(waitMillis, SOCKET_RECEIVE_TIMEOUT);
                waitMillis = Math.max(waitMillis, MIN_SOCKET_RECEIVE_TIMEOUT);
                socket.setSoTimeout((int) waitMillis);

                // --- パケット受信・解析 ---
                Arrays.fill(responseBuffer, (byte) 0);

                try {
                    socket.receive(response);

                    try {
                        DNSMessage responseMsg = new DNSMessage(response.getData(), response.getOffset(),
                                response.getLength());

                        for (DNSAnswer a : responseMsg.getAnswers()) {
                            String answerName = (a.name == null) ? "" : a.name.toLowerCase();
                            if (answerName.endsWith(".")) {
                                answerName = answerName.substring(0, answerName.length() - 1);
                            }

                            boolean matches = answerName.equals(hostLower) ||
                                    answerName.equals(hostWithLocal) ||
                                    answerName.equals(hostWithoutLocal);

                            if (matches && a.type == DNSComponent.Type.A) {
                                String resultIp = a.getRdataString().replace(INET_ADDRESS_PREFIX, "");
                                return resultIp;
                            }
                        }

                    } catch (Exception e) {
                        // 不正なパケットが来ても、ログにエラーを出すだけで処理を止めずにスルーする
                        Log.e(TAG, LOG_PREFIX + "不正なパケットの解析に失敗したためスキップします (Skipped): " + e.getMessage());
                        continue; // ループの先頭に戻って、次のパケット（プリンターからの返事）を待つ
                    }

                } catch (java.net.SocketTimeoutException e) {
                    // 全体のタイムアウト時間を過ぎているか、あるいは次のループに入ってもすぐタイムアウトになる残時間か判定
                    long timeLeft = QUERY_TIMEOUT - (System.currentTimeMillis() - startTime);
                    if (timeLeft <= waitMillis) {
                        // 全体のタイムアウト直前、または過ぎている場合のみログを出す
                        Log.w(TAG, LOG_PREFIX + "socket.receive timed out. Remaining query window is exhausted ("
                                + timeLeft + "ms left).");
                    } else {
                        // 通常のループ途中（タイムアウト未満）であれば、ログを出さずに静かに次のループ（リトライ等）へ進む
                    }
                } catch (Exception e) {
                    Log.e(TAG, LOG_PREFIX + "Error processing received packet", e);
                }
            }

            Log.e(TAG, LOG_PREFIX + "QUERY TIMEOUT: Reached total time limit of " + QUERY_TIMEOUT
                    + "ms without successful resolution.");
            throw new QueryTimeoutException();

        } finally {

            // ===========================
            // 完全クリーンアップ
            // ===========================
            if (socket != null) {
                try {
                    socket.leaveGroup(new java.net.InetSocketAddress(multicastIPAddr, MULTICAST_PORT),
                            this.networkInterface);
                } catch (Exception e) {
                    Log.w(TAG, LOG_PREFIX + "Failed to leave group clean: " + e.getMessage());
                }
                // プリンタ課題エラー対応（リトライ時にsocketが開いたままの場合に失敗するためclose必要）
                socket.close();
            }
            if (multicastLock != null && multicastLock.isHeld()) {
                multicastLock.release();
            }
        }
    }

    public NetworkInterface getWifiNetworkInterface() throws SocketException {
        if (wifiManager == null) {
            Log.e(TAG, LOG_PREFIX + "getWifiNetworkInterface: WifiManager is null");
            throw new SocketException("WifiManager is null");
        }
        WifiInfo wifiInfo = wifiManager.getConnectionInfo();
        if (wifiInfo == null) {
            Log.e(TAG, LOG_PREFIX + "No WiFi connection info available");
            throw new SocketException("No WiFi connection info available");
        }

        int wifiIp = wifiInfo.getIpAddress();
        if (wifiIp == NO_WIFI_IP) {
            Log.e(TAG, LOG_PREFIX + "No valid IP address from WiFi connection");
            throw new SocketException("No valid IP address from WiFi connection");
        }

        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        if (interfaces == null) {
            Log.e(TAG, LOG_PREFIX + "Failed to get network interfaces");
            throw new SocketException("Failed to get network interfaces");
        }

        while (interfaces.hasMoreElements()) {
            NetworkInterface iface = interfaces.nextElement();
            if (iface == null)
                continue;

            if (!iface.isUp() || iface.isLoopback()) {
                continue;
            }

            for (InterfaceAddress ifAddr : iface.getInterfaceAddresses()) {
                if (ifAddr == null || ifAddr.getAddress() == null)
                    continue;

                InetAddress addr = ifAddr.getAddress();

                if (addr instanceof Inet6Address) {
                    continue; // このプラグインはIPv4(Type.A)を探すためIPv6はスキップ
                }

                try {
                    int ifIp = inetAddressToInt(addr);
                    if (wifiIp == ifIp) {
                        return iface;
                    }
                } catch (IllegalArgumentException e) {
                    // IPv4以外のアドレス構造はスキップ
                }
            }
        }

        Log.e(TAG, LOG_PREFIX
                + "getWifiNetworkInterface: No matching interface found matching the Wi-Fi IP.");
        throw new SocketException("No matching network interface found for WiFi");
    }

    /**
     * From android.net.NetworkUtils which is not available
     * @param inetAddr
     * @return
     * @throws IllegalArgumentException
     */
    private static int inetAddressToInt(InetAddress inetAddr)
            throws IllegalArgumentException {

        byte[] addr = inetAddr.getAddress();
        if (addr.length != IPV4_ADDRESS_BYTE_LENGTH) {
            throw new IllegalArgumentException("Not an IPv4 address.");
        }
        return ((addr[3] & 0xff) << 24) |
                ((addr[2] & 0xff) << 16) |
                ((addr[1] & 0xff) << 8) |
                 (addr[0] & 0xff);
    }

}
