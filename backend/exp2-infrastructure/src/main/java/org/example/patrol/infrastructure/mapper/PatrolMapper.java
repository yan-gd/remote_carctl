package org.example.patrol.infrastructure.mapper;

import org.apache.ibatis.annotations.*;
import org.example.patrol.infrastructure.data.*;

import java.util.List;

@Mapper
public interface PatrolMapper {
    @Select("SELECT * FROM sys_user WHERE id=#{id}")
    UserDO userById(long id);

    @Select("SELECT * FROM sys_user WHERE username=#{name} COLLATE NOCASE")
    UserDO userByName(String name);

    @Insert(
            "INSERT INTO sys_user(username,password_hash,role,created_at)"
                + " VALUES(#{username},#{passwordHash},#{role},#{createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertUser(UserDO d);

    @Select("SELECT * FROM patrol_route WHERE id=#{id}")
    RouteDO route(long id);

    @Select("SELECT * FROM route_point WHERE route_id=#{id} ORDER BY sequence")
    List<PointDO> points(long id);

    @Select("SELECT * FROM patrol_route ORDER BY id DESC LIMIT #{size} OFFSET #{offset}")
    List<RouteDO> routes(@Param("size") int size, @Param("offset") long offset);

    @Select("SELECT count(*) FROM patrol_route")
    long routeCount();

    @Insert(
            "INSERT INTO patrol_route(name,description,created_at,updated_at)"
                + " VALUES(#{name},#{description},#{createdAt},#{updatedAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertRoute(RouteDO d);

    @Insert(
            "INSERT INTO route_point(route_id,sequence,x,y,yaw)"
                + " VALUES(#{routeId},#{sequence},#{x},#{y},#{yaw})")
    int insertPoint(PointDO d);

    @Update(
            "UPDATE patrol_route SET"
                + " name=#{name},description=#{description},updated_at=#{updatedAt} WHERE id=#{id}")
    int updateRoute(RouteDO d);

    @Delete("DELETE FROM route_point WHERE route_id=#{id}")
    int deletePoints(long id);

    @Delete("DELETE FROM patrol_route WHERE id=#{id}")
    int deleteRoute(long id);

    @Select("SELECT count(*) FROM patrol_task WHERE route_id=#{id}")
    long routeReferences(long id);

    @Select("SELECT * FROM patrol_task WHERE id=#{id}")
    TaskDO task(long id);

    @Insert(
            "INSERT INTO"
                + " patrol_task(name,route_id,creator_id,planned_at,status,created_at,updated_at)"
                + " VALUES(#{name},#{routeId},#{creatorId},#{plannedAt},#{status},#{createdAt},#{updatedAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertTask(TaskDO d);

    @Update(
            "UPDATE patrol_task SET"
                + " name=#{name},route_id=#{routeId},planned_at=#{plannedAt},status=#{status},updated_at=#{updatedAt}"
                + " WHERE id=#{id}")
    int updateTask(TaskDO d);

    @Delete("DELETE FROM patrol_task WHERE id=#{id}")
    int deleteTask(long id);

    @Select(
            "SELECT * FROM patrol_task WHERE (#{status} IS NULL OR status=#{status}) ORDER BY id"
                + " DESC LIMIT #{size} OFFSET #{offset}")
    List<TaskDO> tasks(
            @Param("size") int size, @Param("offset") long offset, @Param("status") String status);

    @Select("SELECT count(*) FROM patrol_task WHERE (#{status} IS NULL OR status=#{status})")
    long taskCount(@Param("status") String status);

    @Select("SELECT * FROM alarm_record WHERE id=#{id}")
    AlarmDO alarm(long id);

    @Update(
            "UPDATE alarm_record SET"
                + " status=#{status},acknowledged_by=#{acknowledgedBy},acknowledged_at=#{acknowledgedAt}"
                + " WHERE id=#{id} AND status='UNACKNOWLEDGED'")
    int acknowledge(AlarmDO d);

    String ALARM_WHERE =
            " WHERE (#{level} IS NULL OR level=#{level}) AND (#{status} IS NULL OR"
                + " status=#{status}) AND (#{from} IS NULL OR occurred_at>=#{from}) AND (#{to} IS"
                + " NULL OR occurred_at<=#{to})";

    @Select(
            "SELECT * FROM alarm_record"
                    + ALARM_WHERE
                    + " ORDER BY occurred_at DESC,id DESC LIMIT #{size} OFFSET #{offset}")
    List<AlarmDO> alarms(
            @Param("size") int size,
            @Param("offset") long offset,
            @Param("level") String level,
            @Param("status") String status,
            @Param("from") Long from,
            @Param("to") Long to);

    @Select("SELECT count(*) FROM alarm_record" + ALARM_WHERE)
    long alarmCount(
            @Param("level") String level,
            @Param("status") String status,
            @Param("from") Long from,
            @Param("to") Long to);

    @Insert(
            "INSERT INTO alarm_record(title,description,level,status,source,seed_key,occurred_at)"
                + " VALUES('演示告警：测试夹具','仅供接口演示，并非来自真实设备','WARNING','UNACKNOWLEDGED','DEMO_FIXTURE','demo-alarm-v1',#{now})"
                + " ON CONFLICT(seed_key) DO NOTHING")
    int seedDemoAlarm(long now);

    @Insert(
            "INSERT INTO alarm_record(title,description,level,status,source,occurred_at)"
                + " VALUES(#{title},#{description},#{level},#{status},#{source},#{occurredAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertAlarm(AlarmDO d);

    @Insert(
            "INSERT INTO control_log(actor_id,actor_name,operation,target,result,occurred_at,error)"
                + " VALUES(#{actorId},#{actorName},#{operation},#{target},#{result},#{occurredAt},#{error})")
    int appendLog(LogDO d);

    String LOG_WHERE =
            " WHERE (#{actorId} IS NULL OR actor_id=#{actorId}) AND (#{operation} IS NULL OR"
                + " operation=#{operation}) AND (#{from} IS NULL OR occurred_at>=#{from}) AND"
                + " (#{to} IS NULL OR occurred_at<=#{to})";

    @Select(
            "SELECT * FROM control_log"
                    + LOG_WHERE
                    + " ORDER BY occurred_at DESC,id DESC LIMIT #{size} OFFSET #{offset}")
    List<LogDO> logs(
            @Param("actorId") Long actorId,
            @Param("size") int size,
            @Param("offset") long offset,
            @Param("operation") String operation,
            @Param("from") Long from,
            @Param("to") Long to);

    @Select("SELECT count(*) FROM control_log" + LOG_WHERE)
    long logCount(
            @Param("actorId") Long actorId,
            @Param("operation") String operation,
            @Param("from") Long from,
            @Param("to") Long to);
}
