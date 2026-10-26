package org.example.patrol.app;

import org.example.patrol.domain.*;
import org.example.patrol.domain.gateway.*;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.util.*;

/** A bounded live map cache and single command mailbox. Maps persist only on the robot. */
@Service
public class MappingService {
    public record Pose(double x, double y, double yaw) {}
    public record XY(double x, double y) {}
    public record Report(String session, String mode, boolean busy, String mapId, List<String> maps,
                         Pose pose, List<XY> scan, List<XY> path, String commandId, String result, String message) {}
    public record Grid(String session, String mapId, int width, int height, double resolution,
                       double originX, double originY, double originYaw, String cells) {}
    public record Request(String action, String name, String mapId, Pose pose) {}
    public record Command(String id, String session, long expiresAt, Request request) {}
    public record State(boolean online, Report robot, Command pending, String delivery) {}
    private final Clock clock;
    private final UserGateway users;
    private final RobotGateway gateway;
    private final AuditService audit;
    private Report report;
    private Grid grid;
    private long received;
    private Command pending;
    private String delivery;

    public MappingService(Clock clock, UserGateway users, RobotGateway gateway, AuditService audit) {
        this.clock=clock; this.users=users; this.gateway=gateway; this.audit=audit;
    }
    public synchronized State status() {
        expire();
        return new State(online(), report, pending, delivery);
    }
    private boolean online() { return report != null && clock.millis()-received < 5000; }
    private void expire() {
        if (pending != null && clock.millis() > pending.expiresAt()) { pending=null; delivery="EXPIRED"; }
    }
    public synchronized Command exchange(Report next) {
        if (next == null || !id(next.session()) || !Set.of("IDLE","MAPPING","NAVIGATION","ERROR").contains(next.mode()==null?"":next.mode())
                || (next.mapId()!=null && !id(next.mapId())) || next.maps()==null || next.maps().size()>100
                || next.maps().stream().anyMatch(n -> !name(n)) || !pose(next.pose())
                || !points(next.scan(),720) || !points(next.path(),1000)
                || (next.commandId()!=null && !id(next.commandId()))
                || (next.result()!=null && !Set.of("RUNNING","SUCCEEDED","FAILED").contains(next.result()))
                || (next.message()!=null && next.message().length()>200)) throw BusinessException.invalid("无效的地图状态");
        expire();
        if (report == null || !Objects.equals(report.session(),next.session())) {
            grid=null; pending=null; delivery=null;
        }
        report=next; received=clock.millis();
        if (pending!=null && pending.id().equals(next.commandId())) { pending=null; delivery=next.result(); }
        return pending;
    }
    public synchronized void ingest(Grid next) {
        if (!online() || next == null || !Objects.equals(next.session(),report.session())
                || !Objects.equals(next.mapId(),report.mapId()) || !id(next.mapId()))
            throw BusinessException.conflict("地图会话已变化");
        if (next.width()<1 || next.height()<1 || next.width()>512 || next.height()>512
                || !Double.isFinite(next.resolution()) || next.resolution()<0.005 || next.resolution()>100
                || !pose(new Pose(next.originX(),next.originY(),next.originYaw()))
                || next.cells()==null || next.cells().length()>350000) throw BusinessException.invalid("地图超出显示限制");
        byte[] cells;
        try { cells=Base64.getDecoder().decode(next.cells()); }
        catch (IllegalArgumentException e) { throw BusinessException.invalid("无效的地图编码"); }
        if (cells.length!=next.width()*next.height()) throw BusinessException.invalid("地图尺寸不匹配");
        for (byte value:cells) if(value<0 || value>2) throw BusinessException.invalid("无效的栅格值");
        grid=next;
    }
    public synchronized Grid grid() {
        if (!online() || grid==null || !Objects.equals(grid.mapId(),report.mapId()))
            throw BusinessException.unavailable("MAP_UNAVAILABLE","等待当前地图");
        return grid;
    }
    public synchronized Command command(Request request,long actor) {
        var user=users.userById(actor);
        RolePolicy.require(user,Permission.MAP_EDIT);
        expire();
        if (!online()) throw BusinessException.unavailable("MAPPING_OFFLINE","小车 Web 建图服务未连接");
        if (pending!=null || report.busy()) throw BusinessException.conflict("上一操作尚未完成");
        if (request==null || !Set.of("START","SAVE","LOAD","STOP","INITIAL_POSE").contains(request.action()==null?"":request.action()))
            throw BusinessException.invalid("未知地图操作");
        if (Set.of("SAVE","LOAD").contains(request.action()) && !name(request.name()))
            throw BusinessException.invalid("地图名须为1至40位英文字母、数字、下划线或短横线");
        if (request.action().equals("SAVE") && (!report.mode().equals("MAPPING") || report.mapId()==null))
            throw BusinessException.conflict("当前没有正在构建的地图");
        if (request.action().equals("LOAD") && !report.maps().contains(request.name())) throw BusinessException.missing("地图");
        if (request.action().equals("INITIAL_POSE") && (!report.mode().equals("NAVIGATION")
                || request.pose()==null || !pose(request.pose()) || request.mapId()==null || !Objects.equals(request.mapId(),report.mapId())))
            throw BusinessException.conflict("请在当前已加载地图上选择有效的初始位置");
        // A map operation never commands motion. Stop before changing any ROS pipeline.
        gateway.publish(RobotCommand.STOP,clock.instant());
        pending=new Command(UUID.randomUUID().toString(),report.session(),clock.millis()+10000,request);
        delivery="PENDING";
        try { audit.success(user,"MAP_"+request.action(),"map:"+(request.name()==null?"current":request.name())); }
        catch (RuntimeException e) { /* Publishing STOP and queuing a command must remain truthful. */ }
        return pending;
    }
    private static boolean id(String s) { return s!=null && s.matches("[A-Za-z0-9_-]{1,80}"); }
    private static boolean name(String s) { return s!=null && s.matches("[A-Za-z0-9_-]{1,40}"); }
    private static boolean pose(Pose p) { return p==null || finite(p.x()) && finite(p.y()) && Double.isFinite(p.yaw()) && Math.abs(p.yaw())<=Math.PI; }
    private static boolean finite(double v) { return Double.isFinite(v) && Math.abs(v)<=10000; }
    private static boolean points(List<XY> values,int max) {
        return values!=null && values.size()<=max && values.stream().allMatch(p -> p!=null && finite(p.x()) && finite(p.y()));
    }
}
