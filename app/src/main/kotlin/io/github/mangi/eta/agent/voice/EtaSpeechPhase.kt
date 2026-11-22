package io.github.mangi.eta.agent.voice

/** 应用内语音输入的阶段状态，供输入栏与语音控件展示进度。 */
internal enum class EtaSpeechPhase { IDLE, STARTING, LISTENING, RECOGNIZING }
