package com.liskovsoft.youtubeapi.videoinfo.V2;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class ArtworkJavaApiTest {
    @Test public void variantsAndPreparationOverloadsRemainUsefulToJavaConsumers() {
        ArtworkVariant unknown = new ArtworkVariant("https://example.test/unknown");
        ArtworkVariant widthOnly = new ArtworkVariant("https://example.test/width", 48);
        ArtworkVariant small = new ArtworkVariant("https://example.test/small", 48, 48);
        ArtworkVariant large = new ArtworkVariant("https://example.test/large", 176, 176);
        ArrayList<ArtworkVariant> source = new ArrayList<>(Arrays.asList(unknown, widthOnly, small, large));
        Artwork art = new Artwork(source);
        source.clear();
        assertEquals(4, art.getVariants().size());
        assertSame(large, art.getBest());
        assertSame(large, art.select());
        assertSame(small, art.select(40));
        assertSame(large, art.select(88, 88));
        assertSame(large, art.select(1000, 1000));
        assertNull(unknown.getWidth());
        assertNull(widthOnly.getHeight());
        assertFalse(art.toString().contains("https://"));
        try { art.getVariants().clear(); fail("Image variants must be immutable"); }
        catch (UnsupportedOperationException expected) { }

        AtomicInteger requests = new AtomicInteger();
        HttpClient http = request -> { requests.incrementAndGet(); throw new AssertionError("Zero request preparation"); };
        PagePreparation[] overloads = {
            new PagePreparation(), new PagePreparation(http), new PagePreparation(http, 2),
            new PagePreparation(http, 2, 100), new PagePreparation(http, 2, 100, () -> 1L)
        };
        for (PagePreparation preparation : overloads) {
            assertTrue(preparation.prepare(Collections.emptyList()).getOk());
            assertEquals(0, preparation.prepare(Collections.emptyList(), new RequestContext()).getValue().getRequestCount());
            assertTrue(preparation.prepare(Collections.emptyList(), new RequestContext(), 0).getValue().getItems().isEmpty());
            preparation.clear();
        }
        assertEquals(0, requests.get());
    }
}
