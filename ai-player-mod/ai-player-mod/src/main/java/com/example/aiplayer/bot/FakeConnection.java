package com.example.aiplayer.bot;

import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.listener.PacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.text.Text;

/** A network connection that goes nowhere, so the bot can be a real ServerPlayerEntity. */
public class FakeConnection extends ClientConnection {
    public FakeConnection() {
        super(NetworkSide.SERVERBOUND);
    }

    @Override
    public void send(Packet<?> packet) { }

    @Override
    public void send(Packet<?> packet, net.minecraft.network.PacketCallbacks callbacks) { }

    @Override
    public void disconnect(Text reason) { }

    @Override
    public void tick() { }

    @Override
    public void disableAutoRead() { }

    @Override
    public void handleDisconnection() { }

    @Override
    public java.net.SocketAddress getAddress() {
        return new java.net.InetSocketAddress("127.0.0.1", 0);
    }

    @Override
    public boolean isOpen() {
        return true;
    }
}
