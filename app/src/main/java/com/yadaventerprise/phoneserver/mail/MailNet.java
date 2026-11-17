package com.yadaventerprise.phoneserver.mail;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

public final class MailNet {

    private MailNet() {
    }

    public static Socket open(String host, int port, String security, int timeoutMs) throws MailException {
        if (!MailValidation.isHost(host)) throw new MailException("mail server host is invalid");
        if (!MailValidation.isPort(port)) throw new MailException("mail server port is invalid");
        String mode = MailValidation.security(security);
        if (mode.isEmpty()) throw new MailException("mail connections require TLS");
        try {
            if (mode.equals("tls")) {
                SSLSocket ssl = (SSLSocket) SSLSocketFactory.getDefault().createSocket();
                ssl.connect(new InetSocketAddress(host, port), timeoutMs);
                ssl.setSoTimeout(timeoutMs);
                secure(ssl, host, port);
                return ssl;
            }
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            return socket;
        } catch (IOException e) {
            throw new MailException("could not connect to " + host + ":" + port, 502);
        }
    }

    public static SSLSocket upgrade(Socket socket, String host, int port, int timeoutMs) throws MailException {
        try {
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            SSLSocket ssl = (SSLSocket) factory.createSocket(socket, host, port, true);
            ssl.setSoTimeout(timeoutMs);
            secure(ssl, host, port);
            return ssl;
        } catch (IOException e) {
            throw new MailException("TLS negotiation failed for " + host + ":" + port, 502);
        }
    }

    private static void secure(SSLSocket socket, String host, int port) throws IOException {
        SSLParameters parameters = socket.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        socket.setSSLParameters(parameters);
        socket.startHandshake();
    }
}
