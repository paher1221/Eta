package io.github.mangi.eta.ui.app

import android.content.Context
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.model.AgentToolsUiState
import io.github.mangi.eta.ui.model.ToolGroupUi
import io.github.mangi.eta.ui.model.ToolItemUi

internal fun buildToolsState(context: Context): AgentToolsUiState =
    AgentToolsUiState(
        groups = listOf(
            ToolGroupUi(
                id = "text",
                title = context.getString(R.string.state_text_and_clipboard_3a7340),
                tools = listOf(
                    ToolItemUi("set_clipboard", context.getString(R.string.tool_set_clipboard), context.getString(R.string.ui_misc_tool_set_clipboard_desc)),
                    ToolItemUi("get_clipboard", context.getString(R.string.tool_get_clipboard), context.getString(R.string.ui_misc_tool_get_clipboard_desc)),
                ),
            ),
            ToolGroupUi(
                id = "web",
                title = context.getString(R.string.state_web_browsing_e56105),
                tools = listOf(
                    ToolItemUi("web_search", context.getString(R.string.tool_web_search), context.getString(R.string.tool_web_search_description)),
                    ToolItemUi("fetch_url", context.getString(R.string.tool_fetch_url), context.getString(R.string.tool_fetch_url_description)),
                    ToolItemUi("browser_use", context.getString(R.string.tool_ui_agent_browser_a66bd5), context.getString(R.string.tool_ui_open_web_pages_off_screen_and_keep_a_takeover_br_72972e)),
                ),
            ),
            ToolGroupUi(
                id = "file_vision",
                title = context.getString(R.string.state_document_vision_6a65a7),
                tools = listOf(
                    ToolItemUi("read_image", context.getString(R.string.tool_ui_read_pictures_ae993b), context.getString(R.string.tool_ui_read_pictures_of_known_paths_and_hand_them_over__7f9569)),
                    ToolItemUi("generate_image", context.getString(R.string.tool_generate_image), context.getString(R.string.tool_generate_image_description)),
                ),
            ),
            ToolGroupUi(
                id = "memory",
                title = context.getString(R.string.state_memory_b55ff5),
                tools = listOf(
                    ToolItemUi("memory_get", context.getString(R.string.tool_ui_read_memory_979135), context.getString(R.string.tool_ui_paged_to_read_or_retrieve_long_term_memory_in_me_88afc4)),
                    ToolItemUi("memory_write", context.getString(R.string.tool_ui_organize_memory_2b08eb), context.getString(R.string.tool_ui_partially_update_append_or_clear_long_term_memor_c1bab6)),
                    ToolItemUi("character_memory_get", context.getString(R.string.ui_misc_tool_character_memory_get), context.getString(R.string.ui_misc_tool_character_memory_get_desc)),
                    ToolItemUi("character_memory_write", context.getString(R.string.ui_misc_tool_character_memory_write), context.getString(R.string.ui_misc_tool_character_memory_write_desc)),
                ),
            ),
            ToolGroupUi(
                id = "terminal",
                title = context.getString(R.string.state_terminal_and_files_ae7c54),
                tools = listOf(
                    ToolItemUi("terminal", context.getString(R.string.tool_ui_session_terminal_09c6e6), context.getString(R.string.tool_ui_user_root_shell_conversational_execution_and_asy_13c2ab)),
                    ToolItemUi("terminal_job", context.getString(R.string.tool_terminal_job), context.getString(R.string.tool_terminal_job_description)),
                    ToolItemUi("terminal_daemon", context.getString(R.string.tool_terminal_daemon), context.getString(R.string.tool_terminal_daemon_description)),
                    ToolItemUi("edit_file", context.getString(R.string.tool_edit_file), context.getString(R.string.tool_edit_file_description)),
                    ToolItemUi("stat_file", context.getString(R.string.tool_stat_file), context.getString(R.string.tool_stat_file_description)),
                    ToolItemUi("glob_files", context.getString(R.string.tool_glob_files), context.getString(R.string.tool_glob_files_description)),
                    ToolItemUi("grep_files", context.getString(R.string.tool_grep_files), context.getString(R.string.tool_grep_files_description)),
                    ToolItemUi("read_file", context.getString(R.string.tool_ui_read_file_dc995c), context.getString(R.string.tool_ui_read_the_contents_of_mobile_phone_files_bf3066)),
                    ToolItemUi("write_file", context.getString(R.string.tool_ui_write_file_e620fd), context.getString(R.string.tool_ui_write_or_overwrite_mobile_files_29fae4)),
                    ToolItemUi("list_directory", context.getString(R.string.tool_ui_list_directory_96e765), context.getString(R.string.tool_ui_list_directory_contents_feff30)),
                ),
            ),
        )
    )
