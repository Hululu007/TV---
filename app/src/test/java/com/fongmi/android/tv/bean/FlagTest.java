package com.fongmi.android.tv.bean;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class FlagTest {

    @Test
    public void shouldIgnoreOutOfBoundsSavedPosition() {
        Flag flag = new Flag("main");
        Episode first = Episode.create("01", "url1");
        flag.getEpisodes().add(first);
        flag.getEpisodes().add(Episode.create("02", "url2"));
        flag.setPosition(5);

        assertNull(flag.find("unknown", true));
        assertSame(first, flag.find("unknown", false));
    }

    @Test
    public void shouldKeepCurrentEpisodeWhenPrependingNewEpisodes() {
        Flag flag = new Flag("main");
        Episode current = Episode.create("02", "url2");
        flag.getEpisodes().addAll(List.of(current, Episode.create("01", "url1")));
        flag.setPosition(0);

        flag.mergeEpisodes(List.of(Episode.create("03", "url3"), Episode.create("04", "url4")), true);

        assertEquals(2, flag.getPosition());
        assertSame(current, flag.getEpisodes().get(flag.getPosition()));
    }

    @Test
    public void shouldKeepCurrentEpisodeWhenMergingDuplicates() {
        Flag flag = new Flag("main");
        Episode current = Episode.create("01", "url1");
        flag.getEpisodes().add(current);
        flag.setPosition(0);

        flag.mergeEpisodes(List.of(Episode.create("01", "url1"), Episode.create("02", "url2")), true);
        flag.mergeEpisodes(List.of(Episode.create("01", "url1"), Episode.create("02", "url2")), true);

        assertEquals(2, flag.getEpisodes().size());
        assertEquals(1, flag.getPosition());
        assertSame(current, flag.getEpisodes().get(flag.getPosition()));
    }

    @Test
    public void shouldKeepForwardSelectionAndUnsetPositionWhenMerging() {
        Flag flag = new Flag("main");
        Episode current = Episode.create("01", "url1");
        flag.getEpisodes().add(current);
        flag.setPosition(0);
        flag.mergeEpisodes(List.of(Episode.create("02", "url2")), false);
        assertEquals(0, flag.getPosition());
        assertSame(current, flag.getEpisodes().get(flag.getPosition()));

        flag.setPosition(-1);
        flag.mergeEpisodes(List.of(Episode.create("03", "url3")), true);
        assertEquals(-1, flag.getPosition());
        flag.setPosition(100);
        flag.mergeEpisodes(List.of(Episode.create("04", "url4")), true);
        assertEquals(100, flag.getPosition());
    }
}
