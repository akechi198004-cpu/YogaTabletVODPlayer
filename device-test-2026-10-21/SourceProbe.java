import com.hm.video.data.api.XingfanParser;
import com.hm.video.data.model.*;
import java.nio.file.*;

/** Inspect the production parser against saved live HTML without substituting for TV tests. */
public class SourceProbe {
    public static void main(String[] args) throws Exception {
        String html = Files.readString(Path.of(args[1]));
        var parser = XingfanParser.INSTANCE;
        switch (args[0]) {
            case "search":
                var results = parser.parseSearchPage(html, 1);
                System.out.println("RESULTS=" + results.getItems().size());
                results.getItems().stream().limit(12).forEach(w -> System.out.println(w.getTitle() + " " + w.getUrl()));
                break;
            case "home":
                var home = parser.parseHomePage(html);
                System.out.println("HERO=" + home.getHero().size() + " RECENT=" + home.getRecent().size());
                home.getRecent().stream().limit(12).forEach(w -> System.out.println(w.getTitle() + " " + w.getUrl()));
                break;
            case "category":
                var page = parser.parseCategoryPage(html, 1);
                System.out.println("ITEMS=" + page.getItems().size() + " PAGES=" + page.getTotalPages());
                page.getItems().stream().limit(12).forEach(w -> System.out.println(w.getTitle() + " " + w.getUrl()));
                break;
            case "detail":
                var detail = parser.parseDetailPage(html, args[2]);
                System.out.println(detail.getTitle() + " LINES=" + detail.getLines().size());
                for (var line : detail.getLines()) {
                    System.out.println(line.getName() + " EPISODES=" + line.getEpisodes().size());
                    line.getEpisodes().stream().limit(2).forEach(ep -> System.out.println(ep.getNo() + " " + ep.getUrl()));
                }
                break;
            case "play":
                var config = parser.extractPlayerConfig(html);
                System.out.println(config == null ? "NO_PLAYER_CONFIG" : "PLAYER_API=" + config.getApiurl() + " ID=" + config.getUrl());
                break;
        }
    }
}
