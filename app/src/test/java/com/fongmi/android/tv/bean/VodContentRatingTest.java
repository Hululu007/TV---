package com.fongmi.android.tv.bean;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import org.junit.Test;
import org.simpleframework.xml.core.Persister;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class VodContentRatingTest {

    private final Gson gson = new Gson();

    @Test
    public void shouldReadAndTrimExplicitCertificationAliases() {
        for (String key : new String[]{"vod_certification", "content_rating", "age_rating"}) {
            Vod vod = gson.fromJson("{\"" + key + "\":\"  TV-14  \"}", Vod.class);
            assertEquals(key, "TV-14", vod.getContentRating());
        }
    }

    @Test
    public void shouldReturnEmptyForAbsentNullAndBlankCertification() {
        for (String json : new String[]{"{}", "{\"vod_certification\":null}",
                "{\"content_rating\":\"   \"}"}) {
            assertEquals("", gson.fromJson(json, Vod.class).getContentRating());
        }
        Vod vod = new Vod();
        vod.setContentRating(" PG-13 ");
        assertEquals("PG-13", vod.getContentRating());
        vod.setContentRating(null);
        assertEquals("", vod.getContentRating());
    }

    @Test
    public void shouldNotInferCertificationFromTitleRemarksOrScores() {
        String json = "{\"vod_name\":\"Movie 18+\",\"vod_remarks\":\"TV-MA\","
                + "\"vod_score\":\"9.2\",\"douban_score\":9.1,\"rating\":8.5,\"score\":8.3}";
        assertEquals("", gson.fromJson(json, Vod.class).getContentRating());
    }

    @Test
    public void shouldIgnoreMalformedOptionalCertificationWithoutLosingOtherMetadata() {
        for (String value : new String[]{"9.2", "16", "true", "[]", "{}", "{\"rating\":\"PG\"}"}) {
            Vod vod = gson.fromJson("{\"vod_certification\":" + value
                    + ",\"vod_year\":\"2026\"}", Vod.class);
            assertEquals(value, "", vod.getContentRating());
            assertEquals("2026", vod.getYear());
        }
    }

    @Test
    public void shouldSerializeOnlyTheCanonicalCertificationKey() {
        Vod vod = new Vod();
        vod.setContentRating("PG-13");
        JsonObject json = gson.toJsonTree(vod).getAsJsonObject();
        assertEquals("PG-13", json.get("vod_certification").getAsString());
        assertFalse(json.has("content_rating"));
        assertFalse(json.has("age_rating"));
        assertEquals("PG-13", gson.fromJson(json, Vod.class).getContentRating());
    }

    @Test
    public void shouldReadOptionalXmlCertification() throws Exception {
        Persister serializer = new Persister();
        assertEquals("PG-13", serializer.read(Vod.class,
                "<vod><certification> PG-13 </certification></vod>").getContentRating());
        assertEquals("", serializer.read(Vod.class, "<vod><name>Movie</name></vod>").getContentRating());
    }
}
