package org.example.patrol.app;

import org.example.patrol.domain.*;
import org.example.patrol.domain.gateway.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MappingServiceTest {
    TestClock clock=new TestClock();
    UserGateway users=mock(UserGateway.class);
    RobotGateway link=mock(RobotGateway.class);
    MappingService service=new MappingService(clock,users,link,mock(AuditService.class));
    MappingService.Report report(String session,String command,String result,boolean busy) {
        return new MappingService.Report(session,"NAVIGATION",busy,"map-a",List.of("lab"),null,List.of(),List.of(),command,result,null);
    }
    @BeforeEach void setup() {
        when(users.userById(1)).thenReturn(new User(1L,"operator","unused",Role.OPERATOR,clock.instant()));
        when(users.userById(2)).thenReturn(new User(2L,"viewer","unused",Role.VIEWER,clock.instant()));
        service.exchange(report("session-a",null,null,false));
    }
    @Test void commandIsBoundToDeviceSessionAndAcknowledgedWithoutReplay() {
        var c=service.command(new MappingService.Request("LOAD","lab",null,null),1);
        verify(link).publish(RobotCommand.STOP,clock.instant());
        assertEquals(c,service.exchange(report("session-a",null,null,false)));
        assertNull(service.exchange(report("session-a",c.id(),"RUNNING",true)));
        assertThrows(BusinessException.class,()->service.command(c.request(),1));
        service.exchange(report("session-a",c.id(),"SUCCEEDED",false));
        var next=service.command(c.request(),1);
        assertNotEquals(c.id(),next.id());
        assertNull(service.exchange(report("new-session",null,null,false)));
    }
    @Test void expiredOfflineWrongRoleAndPathTraversalAreRejected() {
        var request=new MappingService.Request("LOAD","lab",null,null);
        assertThrows(BusinessException.class,()->service.command(request,2));
        assertThrows(BusinessException.class,()->service.command(new MappingService.Request("SAVE","../map",null,null),1));
        service.command(request,1);clock.advance(10001);
        assertNull(service.exchange(report("session-a",null,null,false)));
        assertEquals("EXPIRED",service.status().delivery());
        clock.advance(5001);assertFalse(service.status().online());
        assertThrows(BusinessException.class,()->service.command(request,1));
    }
    @Test void mapCellsAreBoundedAndInvalidatedByMapOrSessionChange() {
        var good=new MappingService.Grid("session-a","map-a",2,2,0.05,0,0,0,"AAECAQ==");
        service.ingest(good);assertEquals(good,service.grid());
        assertThrows(BusinessException.class,()->service.ingest(new MappingService.Grid("session-a","map-a",513,2,0.05,0,0,0,"AA==")));
        assertThrows(BusinessException.class,()->service.ingest(new MappingService.Grid("session-a","map-a",1,1,0.05,0,0,0,"/w==")));
        assertThrows(BusinessException.class,()->service.command(new MappingService.Request("INITIAL_POSE",null,"old-map",new MappingService.Pose(0,0,0)),1));
        service.exchange(report("new-session",null,null,false));
        assertThrows(BusinessException.class,service::grid);
    }
}
