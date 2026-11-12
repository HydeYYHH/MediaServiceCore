package com.liskovsoft.youtubeapi.videoinfo.V2;

import org.junit.Test;
import static org.junit.Assert.*;

/** Direct Java calls exercise the exact overloads emitted into the published JAR. */
public class PublicCatalogConsumerTest {
    @Test public void usesEveryConstructorAndHomeOverloadWithoutTransportDefaults() {
        AccountSession account = new AccountSession();
        new CatalogSession(account).clearPreparedData();
        HttpClient transport = request -> new HttpReply(200,
            "{\"contents\":{\"richGridRenderer\":{\"contents\":[]}}}", java.util.Collections.emptyMap());
        CatalogSession shortConstructor = new CatalogSession(account, transport);
        CatalogSession fullConstructor = new CatalogSession(account, transport, 0);
        CatalogPage page = shortConstructor.home().getValue();
        assertNotNull(page);
        assertTrue(page.getEmpty());
        assertTrue(page.getItems().isEmpty());
        assertTrue(page.getGroups().isEmpty());
        assertTrue(page.getFilters().isEmpty());
        assertTrue(page.getMessages().isEmpty());
        assertNull(page.getNext());
        assertEquals(0, page.getPreparationRequestCount());
        assertTrue(page.getActions().isEmpty());
        CatalogPage oldShape = new CatalogPage(page.getItems(), page.getGroups(), page.getFilters(),
            page.getNext(), page.getMessages(), page.getPreparationRequestCount());
        assertTrue(oldShape.getActions().isEmpty());
        CatalogPage fullShape = new CatalogPage(page.getItems(), page.getGroups(), page.getFilters(),
            page.getNext(), page.getMessages(), page.getPreparationRequestCount(), page.getActions());
        assertEquals(page.getActions(), fullShape.getActions());
        assertTrue(fullConstructor.home(HomeSection.HOME).getOk());
        CatalogCursor forged = new CatalogCursor(shortConstructor, 0, CatalogRoute.Home.INSTANCE, "handmade");
        assertEquals(RequestFailure.INVALID, shortConstructor.next(forged).getError().getKind());
        CatalogFilter forgedFilter = new CatalogFilter("display", "display", false, true,
            shortConstructor, 0, "query", "handmade");
        assertEquals(RequestFailure.INVALID, shortConstructor.filter(forgedFilter).getError().getKind());
        RequestContext guest = account.installVisitor(new VisitorSession("VISITOR_INFO1_LIVE=profile", "visitor"));
        assertFalse(guest.getSignedIn());
        assertEquals("visitor", guest.getVisitor());
    }
}
