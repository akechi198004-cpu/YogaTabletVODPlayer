package com.hm.video;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.jsoup.Jsoup;
import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 23, manifest = Config.NONE)
public class LegacyParserTest {
    @Test public void jsoupInitializesAndParsesOnApi23() {
        assertEquals("测试影片", Jsoup.parse("<html><a class='title'>测试影片</a></html>")
                .select("a.title").text());
    }
}
