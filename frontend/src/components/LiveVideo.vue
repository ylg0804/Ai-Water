<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, proxyRefs, ref } from 'vue'
import { ArrowDownToLine, MapPin, Maximize2, RefreshCw, Video } from '@lucide/vue'
import type { Device } from '../domain/models'
import { deviceDisplayName } from '../domain/device'
import { useLiveVideo } from '../composables/useLiveVideo'

const props = defineProps<{ device?: Device }>()
const device = computed(() => props.device)
const video = ref<HTMLVideoElement>()
const enabled = computed(() => device.value?.status === 1)
const deviceName = computed(() => device.value ? deviceDisplayName(device.value) : '')
const live = proxyRefs(useLiveVideo({ device, video }))

onMounted(() => { void live.loadStream() })
onBeforeUnmount(live.dispose)
</script>

<template>
  <section class="panel live-video-panel">
    <div class="panel-header">
      <div class="video-heading"><Video :size="17" /><div><h2>监测画面</h2><p>{{ deviceName || '请选择监测设备' }}</p></div></div>
      <span class="stream-label">摄像头直播<span v-if="live.quality"> · {{ live.quality }}</span></span>
    </div>
    <div class="video-stage" :aria-busy="live.loading">
      <video ref="video" v-show="enabled && !live.error" controls muted playsinline preload="none" :aria-label="`${deviceName || '监测设备'}摄像头直播`" @loadedmetadata="live.nativeReady" @error="live.nativeError" @playing="live.onPlaying" @pause="live.onPaused" />
      <div v-if="!enabled || live.loading || live.error" class="video-empty" :role="live.error ? 'alert' : 'status'">
        <RefreshCw v-if="live.loading" :size="30" class="loading-icon" /><Video v-else :size="30" />
        <strong>{{ !device ? '请选择监测设备' : !enabled ? '设备已停用' : live.loading ? '正在连接摄像头' : '暂时无法显示画面' }}</strong>
        <span>{{ !device ? '选择设备后读取对应的直播画面' : !enabled ? '在设备管理中启用后继续查看' : live.loading ? '正在连接视频流' : live.error }}</span>
        <el-button v-if="live.error && enabled" size="small" @click="live.loadStream()">重新连接</el-button>
      </div>
      <div v-if="live.playing && !live.error" class="live-tag"><i /> LIVE <span>{{ device?.serial }}</span></div>
    </div>
    <p v-if="live.captureNotice || live.playbackHint" class="playback-hint" :role="live.captureNotice ? 'alert' : 'status'">{{ live.captureNotice || live.playbackHint }}</p>
    <div class="video-controls">
      <span><MapPin :size="13" />{{ device?.location || '暂无位置信息' }}</span>
      <div>
        <button class="icon-button" :disabled="!enabled || live.loading" aria-label="重新连接视频" title="重新连接视频" @click="live.loadStream()"><RefreshCw :size="15" /></button>
        <button class="icon-button" :disabled="!enabled || live.capturing || live.captureCooldown" :title="live.captureCooldown ? '抓拍间隔至少 4 秒' : '抓拍并下载当前画面'" aria-label="抓拍并下载当前画面" @click="live.capture()"><ArrowDownToLine :size="16" /></button>
        <button class="icon-button" :disabled="!live.ready || Boolean(live.error)" aria-label="全屏播放" title="全屏播放" @click="live.fullscreen()"><Maximize2 :size="16" /></button>
      </div>
    </div>
  </section>
</template>

<style scoped>
.live-video-panel { min-width: 0; }
.video-heading, .video-controls, .video-controls > span, .video-controls > div, .live-tag { display: flex; align-items: center; }
.video-heading { gap: 9px; color: #7d9dcd; }.video-heading h2, .video-heading p { margin: 0; }.video-heading p { color: #64748b; font-size: 11px; margin-top: 3px; }
.stream-label { padding: 4px 7px; color: #64748b; font-size: 11px; border: 1px solid #e6edf7; border-radius: 4px; white-space: nowrap; }
.video-stage { position: relative; margin: 0 17px; aspect-ratio: 16 / 9; overflow: hidden; border-radius: 4px; background: #263449; min-height: 198px; }.video-stage video { display: block; width: 100%; height: 100%; position: absolute; object-fit: contain; }
.video-empty { position: absolute; inset: 0; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 10px; padding: 20px; color: #64748b; background: #f1f5fa; text-align: center; }.video-empty > span { font-size: 11px; line-height: 1.7; overflow-wrap: anywhere; }
.live-tag { position: absolute; top: 10px; left: 10px; gap: 7px; padding: 5px 7px; border-radius: 3px; color: #d0dbea; background: #20324ad1; font-size: 11px; letter-spacing: .6px; pointer-events: none; }.live-tag i { width: 5px; height: 5px; border-radius: 50%; background: #7dc3ae; }.live-tag span { border-left: 1px solid #8aa0b2; padding-left: 7px; }
.video-controls { justify-content: space-between; gap: 6px; padding: 9px 17px; }.video-controls > span { gap: 5px; min-width: 0; color: #64748b; font-size: 11px; }.video-controls > div { gap: 1px; flex-shrink: 0; }.video-controls .icon-button { width: 26px; height: 26px; }.icon-button:disabled { opacity: .35; }
.playback-hint { padding: 8px 17px 0; color: #4f6f9f; font-size: 11px; line-height: 1.7; }.loading-icon { animation: spin 1.4s linear infinite; }@keyframes spin { to { transform: rotate(360deg); } }
@media (max-width: 680px) { .video-stage { min-height: 200px; }.video-controls { padding: 8px 15px; } }
</style>
