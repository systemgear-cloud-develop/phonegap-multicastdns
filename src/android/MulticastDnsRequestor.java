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
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.LinkedList;
import java.util.concurrent.TimeoutException;

/**
 * Created by steve on 16/07/15.
 */
public class MulticastDnsRequestor {

    private static final String TAG = "MulticastDnsRequestor";
    private static final int BUFFER_SIZE = 4096;

    private final int queryTimeout;
    private final Boolean isIPv6;
    private final String logPrefix;
    private final String multicastIP;
    private final int port;
    private final NetworkInterface networkInterface;
    private final InetAddress multicastIPAddr;
    private final WifiManager wifiManager;
    private Context context;
    private MulticastSocket multicastSocket;

    public MulticastDnsRequestor(Context context) throws UnknownHostException, SocketException {
        this((WifiManager) context.getSystemService(Context.WIFI_SERVICE));
        this.context = context; // make sure we live long enough...
    }

    public MulticastDnsRequestor(WifiManager wifiManager) throws UnknownHostException, SocketException {
        // Common multicast ip and port for service discovery
        this("224.0.0.251", 5353, wifiManager);
    }

    public MulticastDnsRequestor(String multicastIP, int port, WifiManager wifiManager)
            throws UnknownHostException, SocketException {
        this(multicastIP, port, null, wifiManager);
    }

    public MulticastDnsRequestor(String multicastIP, int port, Context context)
            throws UnknownHostException, SocketException {
        this(multicastIP, port, null, (WifiManager) context.getSystemService(Context.WIFI_SERVICE));
        this.context = context; // make sure we live long enough...
    }

    public MulticastDnsRequestor(String multicastIP, int port, NetworkInterface networkInterface,
            WifiManager wifiManager) throws UnknownHostException, SocketException {
        this.multicastIP = multicastIP;
        this.multicastIPAddr = InetAddress.getByName(this.multicastIP);
        this.isIPv6 = this.multicastIPAddr instanceof Inet6Address;
        this.logPrefix = this.isIPv6 ? "ipv6: " : "ipv4: ";
        this.port = port;
        this.queryTimeout = 30000; // Milliseconds

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
     * Send a multicast DNS request for the host, then listen until a response for
     * the query is received
     *
     * @param host
     * @return
     * @throws IOException
     */
    public String query(String host) throws IOException, QueryTimeoutException {

        WifiManager.MulticastLock multicastLock = this.wifiManager.createMulticastLock("MulticastDNSRequestor");

        MulticastSocket socket = null;

        try {
            multicastLock.acquire();

            // ===========================
            // ソケットをここで全部作る
            // ===========================
            socket = new MulticastSocket(this.port);
            socket.setReuseAddress(true);
            socket.joinGroup(this.multicastIPAddr);
            socket.setTimeToLive(255);

            // 受信タイムアウト（1秒）
            socket.setSoTimeout(30000);

            byte[] responseBuffer = new byte[BUFFER_SIZE];
            DatagramPacket response = new DatagramPacket(responseBuffer, BUFFER_SIZE);

            DNSMessage[] queries = new DNSMessage[] {
                    new DNSMessage(host, true), // QU
                    new DNSMessage(host, false) // QM
            };

            long startTime = System.currentTimeMillis();

            // ===========================
            // mDNS クエリ送信
            // ===========================
            for (DNSMessage qmsg : queries) {
                byte[] queryBytes = qmsg.serialize();
                DatagramPacket request = new DatagramPacket(
                        queryBytes, queryBytes.length, multicastIPAddr, port);

                Log.i(TAG, logPrefix + "Sending Request: " + qmsg);
                socket.send(request);
            }

            // ===========================
            // 応答受信ループ
            // ===========================
            while (System.currentTimeMillis() - startTime < queryTimeout) {

                Arrays.fill(responseBuffer, (byte) 0);

                try {
                    Log.d(TAG, logPrefix + "About to receive");
                    socket.receive(response);
                    Log.d(TAG, logPrefix + "Received. Processing...");

                    DNSMessage responseMsg = new DNSMessage(response.getData(), response.getOffset(),
                            response.getLength());

                    String hostLower = (host == null) ? "" : host.toLowerCase();
                    if (hostLower.endsWith(".")) {
                        hostLower = hostLower.substring(0, hostLower.length() - 1);
                    }

                    String hostWithLocal = hostLower.endsWith(".local")
                            ? hostLower
                            : hostLower + ".local";

                    for (DNSAnswer a : responseMsg.getAnswers()) {

                        String answerName = (a.name == null) ? "" : a.name.toLowerCase();
                        if (answerName.endsWith(".")) {
                            answerName = answerName.substring(0, answerName.length() - 1);
                        }

                        boolean matches = answerName.equals(hostLower) ||
                                answerName.equals(hostWithLocal) ||
                                (hostLower.endsWith(".local") &&
                                        answerName.equals(
                                                hostLower.replaceFirst("\\.local$", "")));

                        if (matches && a.type == DNSComponent.Type.A) {
                            Log.i(TAG, logPrefix + "Got IPv4 answer: " + a);
                            return a.getRdataString().replace("/", "");
                        }
                    }

                } catch (SocketTimeoutException e) {
                    Log.v(TAG, logPrefix + "Timeout waiting for packet");
                }
            }

            // タイムアウト
            throw new QueryTimeoutException();

        } finally {

            // ===========================
            // 完全クリーンアップ
            // ===========================
            if (socket != null) {
                try {
                    socket.leaveGroup(multicastIPAddr);
                } catch (Exception ignored) {
                }

                try {
                    socket.close();
                } catch (Exception ignored) {
                }
            }

            if (multicastLock.isHeld()) {
                multicastLock.release();
            }
        }
    }

    private void openSocket() throws IOException {
        this.multicastSocket = new MulticastSocket(this.port);
        multicastSocket.setTimeToLive(2);
        multicastSocket.setReuseAddress(true);
        multicastSocket.setNetworkInterface(networkInterface);
        multicastSocket.joinGroup(this.multicastIPAddr);
        multicastSocket.setSoTimeout(30000);
    }

    public NetworkInterface getWifiNetworkInterface() throws SocketException {
        WifiInfo wifiInfo = wifiManager.getConnectionInfo();
        if (wifiInfo == null) {
            Log.e(TAG, this.logPrefix + "No WiFi connection info available");
            throw new SocketException("No WiFi connection info available");
        }

        int wifiIp = wifiInfo.getIpAddress();
        if (wifiIp == 0) {
            Log.e(TAG, this.logPrefix + "No valid IP address from WiFi connection");
            throw new SocketException("No valid IP address from WiFi connection");
        }

        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        if (interfaces == null) {
            Log.e(TAG, this.logPrefix + "Failed to get network interfaces");
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

                try {
                    int ifIp = inetAddressToInt(ifAddr.getAddress());
                    Log.d(TAG, this.logPrefix + String.format("Comparing WiFi IP %s with interface IP %s on %s", wifiIp,
                            ifIp, iface.getName()));

                    if (wifiIp == ifIp) {
                        Log.i(TAG, this.logPrefix + String.format("Using %s as the network interface (IP: %s)",
                                iface.getName(), wifiInfo.getIpAddress()));
                        return iface;
                    }
                } catch (IllegalArgumentException e) {
                    Log.d(TAG, this.logPrefix + "Skipping non-IPv4 address on " + iface.getName());
                }
            }
        }

        Log.e(TAG, this.logPrefix
                + String.format("No matching network interface found for WiFi IP: %s", wifiInfo.getIpAddress()));
        throw new SocketException("No matching network interface found for WiFi");
    }

    /**
     * From android.net.NetworkUtils which is not available
     * 
     * @param inetAddr
     * @return
     * @throws IllegalArgumentException
     */
    private static int inetAddressToInt(InetAddress inetAddr)
            throws IllegalArgumentException {

        byte[] addr = inetAddr.getAddress();
        return ((addr[3] & 0xff) << 24) |
                ((addr[2] & 0xff) << 16) |
                ((addr[1] & 0xff) << 8) |
                (addr[0] & 0xff);
    }

}
