package com.liskovsoft.youtubeapi.videoinfo.V2;

import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class VisitorJavaApiTest {
    @Test public void persistedAnonymousStateAndEveryContextOverloadRemainUsableFromJava() {
        VisitorSession visitor = new VisitorSession("YSC=fixture", "visitor-data");
        assertEquals("YSC=fixture", visitor.getCookies());
        assertEquals("visitor-data", visitor.getVisitorData());
        assertEquals("en", visitor.requestContext().getLanguage());
        assertEquals("fr", visitor.requestContext("fr").getLanguage());
        assertEquals("CA", visitor.requestContext("fr", "CA").getRegion());
        assertEquals(8, visitor.requestContext("fr", "CA", 8).getGeneration());
        assertFalse(visitor.toString().contains("fixture"));
        HttpClient bootstrap = request -> new HttpReply(200,
            "<script>ytcfg.set({\"VISITOR_DATA\":\"fresh-visitor\"});</script>", Collections.emptyMap());
        assertEquals("fresh-visitor", VisitorSessionKt.loadVisitorSession(visitor.requestContext(), bootstrap).getValue().getVisitorData());
        RequestContext delegated = new RequestContext(null, null, "en", "US", 0, "brand", null, null);
        assertEquals("visitor_requires_anonymous_context", VisitorSessionKt.loadVisitorSession(delegated).getError().getCode());
        assertEquals("missing_watch_identity", WatchHistoryKt.loadWatchTracking("video", new RequestContext()).getError().getCode());
        HttpClient tracking = request -> new HttpReply(200,
            "{\"playabilityStatus\":{\"status\":\"UNPLAYABLE\"}}", Collections.emptyMap());
        assertEquals("playability_unplayable", WatchHistoryKt.loadWatchTracking("video", visitor.requestContext(), tracking).getError().getCode());
    }
}
