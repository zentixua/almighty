package ua.zentix.almighty.mcp;

import com.google.gson.JsonObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * What an owner needs to connect an agent: the MCP address, the one line each product's agent runs to add it, and
 * secret-free one-click links (sign-in is OAuth, so a link carries only the address). Cloud products (claude.ai,
 * ChatGPT on the web) appear only when {@code bridge.public_url} gives a public HTTPS address.
 */
public final class Connect {
    private Connect() {}

    /** One way to connect: product, what to do, and a link to click (or null). */
    public record Way(String product, String how, String link) {}

    public static List<Way> ways(String mcpUrl, boolean publicHttps) {
        List<Way> out = new ArrayList<>();
        out.add(new Way("Any agent", "Tell it: connect to my Minecraft server at " + mcpUrl, null));
        out.add(new Way("Claude Code", "claude mcp add --transport http almighty " + mcpUrl + "   then /mcp (or claude mcp login almighty)", null));
        out.add(new Way("Codex and the ChatGPT desktop app (Codex mode)", "codex mcp add almighty --url " + mcpUrl
                + "   then codex mcp login almighty", null));
        JsonObject vscode = new JsonObject();
        vscode.addProperty("name", "almighty");
        vscode.addProperty("type", "http");
        vscode.addProperty("url", mcpUrl);
        out.add(new Way("VS Code (Copilot agent mode)", "code --add-mcp '" + vscode + "'", "vscode:mcp/install?" + enc(vscode.toString())));
        JsonObject url = new JsonObject();
        url.addProperty("url", mcpUrl);
        String b64 = Base64.getEncoder().encodeToString(url.toString().getBytes(StandardCharsets.UTF_8));
        out.add(new Way("Cursor", "Settings → MCP → Add, or the link", "cursor://anysphere.cursor-deeplink/mcp/install?name=almighty&config=" + enc(b64)));
        out.add(new Way("opencode", "opencode mcp add almighty --url " + mcpUrl + "   then opencode mcp auth almighty", null));
        out.add(new Way("Gemini CLI", "gemini mcp add -t http almighty " + mcpUrl, null));
        out.add(new Way("LM Studio (local models: add ?profile=compact&images=false)", "the link", "https://lmstudio.ai/install-mcp?name=almighty&config=" + enc(b64)));
        out.add(new Way("Goose", "the link", "goose://extension?url=" + enc(mcpUrl) + "&type=streamable_http&id=almighty&name=Almighty"));
        if (publicHttps) {
            out.add(new Way("claude.ai, Claude Desktop chat", "the link, then Connect", "https://claude.ai/customize/connectors?modal=add-custom-connector&connectorName=Almighty&connectorUrl=" + enc(mcpUrl)));
            out.add(new Way("ChatGPT on the web (advanced)", "Settings → Apps → Advanced → Developer mode, then Create: " + mcpUrl, null));
        }
        return out;
    }

    static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
