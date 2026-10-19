package dev.codingchest.net;

import dev.codingchest.core.Model.*;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import java.util.*;
import java.util.function.Consumer;

/** Bounded, expiring application fragments; no filesystem paths or commands cross the network. */
public final class Wire {
    public static final int PART=16000,MAX_PARTS=128,MAX_CHARS=1_500_000;
    public record Message(String id,String kind,int index,int total,String data) implements CustomPayload {
        public static final Id<Message> ID=new Id<>(Identifier.of("codingchest","message"));
        public static final PacketCodec<RegistryByteBuf,Message> CODEC=CustomPayload.codecOf((m,b)->{
            b.writeString(m.id,40);b.writeString(m.kind,32);b.writeVarInt(m.index);b.writeVarInt(m.total);b.writeString(m.data,PART);
        },b->new Message(b.readString(40),b.readString(32),b.readVarInt(),b.readVarInt(),b.readString(PART)));
        public Id<? extends CustomPayload> getId(){return ID;}
    }
    public record ScanRequest(EncodingProfile profile,GroupConfig config,String token) {}
    public record ScanResult(GroupConfig config,List<LayoutEntry> entries,String token) {}
    public record PlacementRequest(LayoutProject project,Pos origin,int rotation) {}
    public static void send(String kind,String data,Consumer<Message> sink) {
        if(data.length()>MAX_CHARS)throw new IllegalArgumentException("项目数据过大，请减少扫描内容");
        String id=UUID.randomUUID().toString();int total=Math.max(1,(data.length()+PART-1)/PART);
        for(int i=0;i<total;i++)sink.accept(new Message(id,kind,i,total,data.substring(i*PART,Math.min(data.length(),(i+1)*PART))));
    }
    public static final class Assembler {
        private String id="",kind="";private String[] parts;private long deadline;private int length;
        public Optional<String> accept(Message m) {
            if(m.total()<1||m.total()>MAX_PARTS||m.index()<0||m.index()>=m.total()||m.data().length()>PART)throw new IllegalArgumentException("非法消息分片");
            long now=System.currentTimeMillis();
            if(!id.equals(m.id())||now>deadline) {
                if(m.index()!=0)throw new IllegalArgumentException("消息缺少首片，请重试");
                id=m.id();kind=m.kind();parts=new String[m.total()];length=0;deadline=now+30000;
            }
            if(parts.length!=m.total()||!kind.equals(m.kind())||parts[m.index()]!=null)throw new IllegalArgumentException("消息分片不一致");
            length+=m.data().length();if(length>MAX_CHARS)throw new IllegalArgumentException("消息超长");parts[m.index()]=m.data();
            for(String part:parts)if(part==null)return Optional.empty();String out=String.join("",parts);id="";parts=null;return Optional.of(out);
        }
    }
}
