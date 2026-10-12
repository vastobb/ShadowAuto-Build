package com.phantom.automation.ui;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

import com.phantom.automation.R;

/** MCP/OpenCode setup checklist and connection values. */
public final class McpSetupActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mcp_setup);
        ((TextView) findViewById(R.id.mcpBody)).setText(
                "1. Start the daemon on the Home screen (Shizuku READY required).\n\n"
                        + "2. On the Fedora container (different loopback than Android), bridge RPC:\n"
                        + "     adb forward tcp:43110 tcp:43110\n\n"
                        + "3. Install the Python MCP server:\n"
                        + "     python3 -m pip install -r mcp/requirements.txt\n\n"
                        + "4. Add to your OpenCode project config (opencode.json):\n"
                        + "     \"shadowauto\" -> type local, command:\n"
                        + "     [\"python3\", \"<repo>/mcp/mcp_server.py\"]\n"
                        + "     env SHADOWAUTO_HOST=127.0.0.1, SHADOWAUTO_PORT=43110\n\n"
                        + "5. opencode mcp list  (expect: shadowauto connected)\n\n"
                        + "Flow: check_connection > open_session > list_apps > launch_app > "
                        + "loop(dump_ui > tap/tap_target/swipe/scroll/focus_input+input_text) > close_session.\n"
                        + "Coordinates are display-local; screenshots are base64 PNG of the virtual display.\n\n"
                        + "Local model endpoint (controller/API compatibility):\n"
                        + "     python3 mcp/model_service.py --port 8410\n"
                        + "     API URL http://127.0.0.1:8410/v1   Model phantom-automation\n"
                        + "     (device needs: adb reverse tcp:8410 tcp:8410)");
    }
}
