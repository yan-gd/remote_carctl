package org.example.patrol.app;

import org.example.patrol.domain.BusinessException;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;

class CameraRelayTest {
    static byte[] jpeg(int value) { return new byte[]{(byte)255,(byte)216,(byte)value,(byte)255,(byte)217}; }

    @Test void noHistoryIsStoredForAFutureViewer() throws Exception {
        var relay=new CameraRelay();
        assertFalse(relay.status().online());
        assertEquals(0,relay.accept(jpeg(1)));
        try(var viewer=relay.subscribe()) {
            assertNull(viewer.poll(1));
            assertEquals(0,relay.status().forwardedFrames());
            var current=jpeg(2);
            assertEquals(1,relay.accept(current));
            assertArrayEquals(current,viewer.poll(10));
            assertNull(viewer.poll(1));
            assertTrue(relay.status().online());
        }
        assertEquals(0,relay.status().viewers());
    }

    @Test void slowViewerGetsOnlyNewestFrameAndClosingReleasesIt() throws Exception {
        var relay=new CameraRelay();
        var first=relay.subscribe(); var second=relay.subscribe();
        relay.accept(jpeg(1)); relay.accept(jpeg(2));
        assertArrayEquals(jpeg(2),first.poll(1));
        second.close(); second.close();
        assertNull(second.poll(1));
        assertEquals(1,relay.status().viewers());
        first.close();
    }

    @Test void stalePendingFrameIsDiscarded() throws Exception {
        var relay=new CameraRelay();
        try(var viewer=relay.subscribe()) {
            relay.accept(jpeg(1));
            Thread.sleep(1050);
            assertNull(viewer.poll(1));
        }
    }

    @Test void rejectsOversizeMalformedFramesAndExcessConnections() {
        var relay=new CameraRelay();
        for(byte[] bad:new byte[][]{null,new byte[3],new byte[]{1,2,3,4},new byte[CameraRelay.MAX_FRAME_BYTES+1]})
            assertThrows(BusinessException.class,()->relay.accept(bad));
        var viewers=new ArrayList<CameraRelay.Subscription>();
        for(int i=0;i<CameraRelay.MAX_VIEWERS;i++) viewers.add(relay.subscribe());
        assertEquals("CAMERA_BUSY",assertThrows(BusinessException.class,relay::subscribe).getCode());
        viewers.forEach(CameraRelay.Subscription::close);
        assertEquals(0,relay.status().viewers());
    }
}
