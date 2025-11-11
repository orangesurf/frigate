package com.sparrowwallet.frigate.electrum;

import com.github.arteam.simplejsonrpc.client.Transport;

import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class ElectrumNotificationTransport implements Transport {
    private final Socket clientSocket;

    public ElectrumNotificationTransport(Socket clientSocket) {
        this.clientSocket = clientSocket;
    }

    @Override
    public String pass(String request) throws IOException {
        byte[] bytes = (request + "\n").getBytes(StandardCharsets.UTF_8);
        clientSocket.getOutputStream().write(bytes);
        clientSocket.getOutputStream().flush();

        return "{\"result\":{},\"error\":null,\"id\":1}";
    }
}
