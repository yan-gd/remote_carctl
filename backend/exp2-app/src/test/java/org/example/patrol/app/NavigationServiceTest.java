package org.example.patrol.app;

import org.example.patrol.client.NavigationModels;
import org.example.patrol.domain.*;
import org.example.patrol.domain.gateway.*;
import org.junit.jupiter.api.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class NavigationServiceTest {
    final RobotGateway link=mock(RobotGateway.class);
    final RobotMonitor monitor=mock(RobotMonitor.class);
    final UserGateway users=mock(UserGateway.class);
    final AuditService audit=mock(AuditService.class);
    final TestClock clock=new TestClock();
    NavigationService service;
    NavigationModels.Start request(String map) {
        return new NavigationModels.Start(map,List.of(new NavigationModels.Goal(1.0,2.0,0.0)),1);
    }
    @BeforeEach void setup() {
        when(users.userById(1)).thenReturn(new User(1L,"op","",Role.OPERATOR,clock.instant()));
        when(users.userById(2)).thenReturn(new User(2L,"mon","",Role.MONITOR,clock.instant()));
        when(users.userById(3)).thenReturn(new User(3L,"view","",Role.VIEWER,clock.instant()));
        when(users.userById(4)).thenReturn(new User(4L,"other","",Role.OPERATOR,clock.instant()));
        when(link.connected()).thenReturn(true);
        when(link.publishNavigation(any(),any())).thenReturn(42L);
        when(monitor.navigation()).thenReturn(new NavigationReport("SCOUT-001",true,"READY","map-id","map","IDLE",null,0,0,0.0,0.0,0.0));
        service=new NavigationService(link,monitor,users,audit,clock,true);
    }
    NavigationReport report(String state,String id) {
        return new NavigationReport("SCOUT-001",true,"READY","map-id","map",state,id,0,1,0.0,0.0,0.0);
    }
    @Test void publishesMapGoalsAndBackendRenewsWithoutABrowser() {
        var accepted=service.start(request("map-id"),1);
        assertTrue(accepted.accepted()); assertEquals(42,accepted.seq());
        verify(link).publishNavigation(argThat(o->o.action().equals("START") && o.mapId().equals("map-id") && o.points().size()==1),eq(clock.instant()));
        verify(audit).success(any(),eq("NAVIGATION_START"),contains(accepted.missionId()));
        assertThrows(BusinessException.class,()->service.start(request("map-id"),1));
        assertEquals(1L,service.status().ownerId());
        when(monitor.navigation()).thenReturn(report("ACTIVE",accepted.missionId()));
        for(int i=0;i<6;i++) { clock.advance(1000); service.renewLease(); }
        verify(link,times(6)).publishNavigation(argThat(o->o.action().equals("KEEPALIVE") && o.missionId().equals(accepted.missionId())),any());
        assertTrue(service.status().active());
    }
    @Test void rolesDisabledOfflineMapMismatchAndInvalidGoalFailBeforePublish() {
        assertThrows(BusinessException.class,()->service.start(request("map-id"),3));
        assertThrows(BusinessException.class,()->service.start(request("map-id"),2));
        var disabled=new NavigationService(link,monitor,users,audit,clock,false);
        assertFalse(disabled.status().ready());
        assertEquals("NAVIGATION_DISABLED",assertThrows(BusinessException.class,()->disabled.start(request("map-id"),1)).getCode());
        assertThrows(BusinessException.class,()->service.start(request("wrong"),1));
        assertThrows(BusinessException.class,()->service.start(new NavigationModels.Start("map-id",List.of(new NavigationModels.Goal(Double.NaN,0.0,0.0)),1),1));
        when(monitor.navigation()).thenReturn(null);
        assertFalse(service.status().ready());
        assertThrows(BusinessException.class,()->service.start(request("map-id"),1));
        when(link.connected()).thenReturn(false);
        assertEquals("MQTT_UNAVAILABLE",assertThrows(BusinessException.class,()->service.start(request("map-id"),1)).getCode());
        verify(link,never()).publishNavigation(any(),any());
    }
    @Test void monitorCancelSendsStopFirstEvenWhenNavigationPublishFails() {
        when(link.publishNavigation(any(),any())).thenThrow(new IllegalStateException("test transport failure"));
        assertThrows(IllegalStateException.class,()->service.cancel(2));
        var order=inOrder(link);
        order.verify(link).publish(eq(RobotCommand.STOP),any());
        order.verify(link).publishNavigation(argThat(o->o.action().equals("CANCEL")),any());
        assertThrows(BusinessException.class,()->service.cancel(3));
    }

    @Test void terminalRobotResultEndsRenewalAndDoesNotInventTaskCompletion() {
        var accepted=service.start(request("map-id"),1);
        when(monitor.navigation()).thenReturn(report("SUCCEEDED",accepted.missionId()));
        service.renewLease();
        assertFalse(service.status().active());
        service.renewLease();
        verify(link,times(1)).publishNavigation(any(),any());
        verify(audit).success(any(),eq("NAVIGATION_END"),contains(":SUCCEEDED"));
    }

    @Test void missingRobotConfirmationOrLongPauseCannotReviveAnExpiredMission() {
        var accepted=service.start(request("map-id"),1);
        clock.advance(1000);service.renewLease(); // Still old IDLE report: no renewal.
        clock.advance(2100);
        when(monitor.navigation()).thenReturn(report("ACTIVE",accepted.missionId()));
        service.renewLease();
        assertFalse(service.status().active());
        verify(link).publish(RobotCommand.STOP,clock.instant());
        verify(link,times(1)).publishNavigation(any(),any());
    }

    @Test void brokerFailureDoesNotExtendTheLease() {
        var accepted=service.start(request("map-id"),1);
        when(monitor.navigation()).thenReturn(report("ACTIVE",accepted.missionId()));
        when(link.publishNavigation(argThat(o->o.action().equals("KEEPALIVE")),any()))
                .thenThrow(new IllegalStateException("test dependency outage"));
        clock.advance(1000);service.renewLease();
        clock.advance(2100);service.renewLease();
        assertFalse(service.status().active());
        verify(link).publish(RobotCommand.STOP,clock.instant());
    }

    @Test void revokedOperatorPermissionStopsAutonomousRenewal() {
        var accepted=service.start(request("map-id"),1);
        when(monitor.navigation()).thenReturn(report("ACTIVE",accepted.missionId()));
        when(users.userById(1)).thenReturn(new User(1L,"op","",Role.VIEWER,clock.instant()));
        service.renewLease();
        assertFalse(service.status().active());
        verify(link).publish(RobotCommand.STOP,clock.instant());
        verify(audit).success(any(),eq("NAVIGATION_END"),contains("PERMISSION_REVOKED"));
    }

    @Test void relativeGoalsAreSingleShotBoundedAndKeepTheirBodyCoordinatesOnTheWire() {
        when(monitor.navigation()).thenReturn(new NavigationReport("SCOUT-001",true,"READY","relative-session",
                "odom","IDLE",null,0,0,10.0,20.0,1.57,"relative"));
        var goal = new NavigationModels.Goal(2.0,1.0,0.0);
        assertThrows(BusinessException.class,()->service.start(new NavigationModels.Start("relative-session",List.of(goal),2),1));
        assertThrows(BusinessException.class,()->service.start(new NavigationModels.Start("relative-session",List.of(goal,goal),1),1));
        for (var point : List.of(new NavigationModels.Goal(3.0,3.0,0.0),new NavigationModels.Goal(0.0,0.0,0.0)))
            assertThrows(BusinessException.class,()->service.start(new NavigationModels.Start("relative-session",List.of(point),1),1));
        verify(link,never()).publishNavigation(any(),any());
        var result = service.start(new NavigationModels.Start("relative-session",List.of(goal),1),1);
        assertTrue(result.accepted());
        verify(link).publishNavigation(argThat(o->o.mode().equals("relative") && o.points().get(0).x()==2
                && o.points().get(0).y()==1 && o.cycles()==1),any());
        assertEquals("relative",service.status().robot().mode());
    }

    @Test void previousOdomSessionIsRejectedBeforePublishing() {
        when(monitor.navigation()).thenReturn(new NavigationReport("SCOUT-001",true,"READY","relative-new",
                "odom","IDLE",null,0,0,0.0,0.0,0.0,"relative"));
        assertThrows(BusinessException.class,()->service.start(request("relative-old"),1));
        verify(link,never()).publishNavigation(any(),any());
    }
}
