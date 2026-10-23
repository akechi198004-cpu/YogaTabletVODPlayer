import React, { useState, useEffect, useRef } from 'react';
import {
  X,
  Play,
  Pause,
  RotateCcw,
  RotateCw,
  Volume2,
  VolumeX,
  SkipForward,
  Minimize2,
  Tv,
  Sparkles,
  Lock,
  Unlock,
  MessageSquare,
  Send,
  Radio,
  Clock,
  Layers,
  ShieldCheck,
  Film,
  Rotate3d,
  Smartphone
} from 'lucide-react';
import { MediaWork, DanmakuItem, MOCK_DANMAKU } from '../data/mockCatalog';
import { LoadingHUD } from './LoadingHUD';

interface CinemaPlayerModalProps {
  work: MediaWork;
  currentEpisode: number;
  isOpen: boolean;
  onClose: () => void;
  onEpisodeChange: (ep: number) => void;
  isPlaying: boolean;
  setIsPlaying: (playing: boolean) => void;
  currentTime: number;
  setCurrentTime: React.Dispatch<React.SetStateAction<number>> | ((fn: any) => void);
  duration: number;
}

export const CinemaPlayerModal: React.FC<CinemaPlayerModalProps> = ({
  work,
  currentEpisode,
  isOpen,
  onClose,
  onEpisodeChange,
  isPlaying,
  setIsPlaying,
  currentTime,
  setCurrentTime,
  duration
}) => {
  const [showControls, setShowControls] = useState(true);
  const [speed, setSpeed] = useState('1.0x');
  const [line, setLine] = useState(0);
  const [quality, setQuality] = useState('4K 蓝光 60FPS');
  const [aspectRatio, setAspectRatio] = useState<'fit' | '16:9' | 'fill'>('16:9');
  const [isLandscape, setIsLandscape] = useState(true);
  const [volume, setVolume] = useState(0.85);
  const [isMuted, setIsMuted] = useState(false);
  const [isLocked, setIsLocked] = useState(false);
  const [showEpisodeDrawer, setShowEpisodeDrawer] = useState(false);
  const [showDanmaku, setShowDanmaku] = useState(true);
  const [danmakuInput, setDanmakuInput] = useState('');
  const [danmakuList, setDanmakuList] = useState<DanmakuItem[]>(MOCK_DANMAKU);
  const [hoverTime, setHoverTime] = useState<number | null>(null);
  const [currentTimeStr, setCurrentTimeStr] = useState('12:00');
  const [isBuffering, setIsBuffering] = useState(false);

  const containerRef = useRef<HTMLDivElement>(null);
  const controlsTimeoutRef = useRef<NodeJS.Timeout | null>(null);

  // Update real-time clock
  useEffect(() => {
    const updateClock = () => {
      const now = new Date();
      const h = String(now.getHours()).padStart(2, '0');
      const m = String(now.getMinutes()).padStart(2, '0');
      setCurrentTimeStr(`${h}:${m}`);
    };
    updateClock();
    const interval = setInterval(updateClock, 10000);
    return () => clearInterval(interval);
  }, []);

  // Format seconds into MM:SS
  const formatTime = (sec: number) => {
    const m = Math.floor(sec / 60);
    const s = Math.floor(sec % 60);
    return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
  };

  // Auto-hide controls
  const resetControlsTimeout = () => {
    if (isLocked) return;
    setShowControls(true);
    if (controlsTimeoutRef.current) {
      clearTimeout(controlsTimeoutRef.current);
    }
    if (isPlaying) {
      controlsTimeoutRef.current = setTimeout(() => {
        setShowControls(false);
      }, 3500);
    }
  };

  useEffect(() => {
    if (!isOpen) return;
    resetControlsTimeout();
    return () => {
      if (controlsTimeoutRef.current) clearTimeout(controlsTimeoutRef.current);
    };
  }, [isPlaying, isOpen, isLocked]);

  // Keyboard shortcuts
  useEffect(() => {
    if (!isOpen) return;
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.target instanceof HTMLInputElement) return;
      if (e.key === 'Escape') {
        onClose();
      } else if (e.key === ' ' || e.code === 'Space') {
        e.preventDefault();
        setIsPlaying(!isPlaying);
        resetControlsTimeout();
      } else if (e.key === 'ArrowLeft') {
        e.preventDefault();
        setCurrentTime(Math.max(0, currentTime - 10));
        resetControlsTimeout();
      } else if (e.key === 'ArrowRight') {
        e.preventDefault();
        setCurrentTime(Math.min(duration, currentTime + 10));
        resetControlsTimeout();
      } else if (e.key === 'ArrowUp') {
        e.preventDefault();
        setVolume(v => Math.min(1, v + 0.1));
        setIsMuted(false);
      } else if (e.key === 'ArrowDown') {
        e.preventDefault();
        setVolume(v => Math.max(0, v - 0.1));
      } else if (e.key.toLowerCase() === 'r') {
        e.preventDefault();
        setIsLandscape(prev => !prev);
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [isOpen, isPlaying, currentTime, duration]);

  const handleSendDanmaku = (e: React.FormEvent) => {
    e.preventDefault();
    if (!danmakuInput.trim()) return;
    const newDanmaku: DanmakuItem = {
      id: Date.now().toString(),
      text: danmakuInput.trim(),
      timeSec: currentTime,
      color: '#00d2ff',
      topPercent: 15 + Math.floor(Math.random() * 45)
    };
    setDanmakuList(prev => [...prev, newDanmaku]);
    setDanmakuInput('');
  };

  if (!isOpen) return null;

  const totalEpisodes = work.totalEps || (work.mainType === 'movie' ? 1 : 24);
  const epArray = Array.from({ length: totalEpisodes }, (_, i) => i + 1);

  return (
    <div
      ref={containerRef}
      onMouseMove={resetControlsTimeout}
      onClick={resetControlsTimeout}
      className="fixed inset-0 z-[100] bg-[#05070c] text-slate-100 flex flex-col justify-between overflow-hidden select-none font-sans"
    >
      {/* Ambient Theater Backlight Glow */}
      <div className="absolute inset-0 pointer-events-none opacity-40 blur-3xl transition-all duration-1000 bg-gradient-to-tr from-cyan-950/40 via-transparent to-blue-900/40" />

      {/* Main Video Viewport Canvas */}
      <div className="absolute inset-0 flex items-center justify-center overflow-hidden">
        <div
          className={`relative transition-all duration-500 flex items-center justify-center ${
            isLandscape
              ? aspectRatio === '16:9'
                ? 'w-full h-full aspect-video max-h-screen'
                : aspectRatio === 'fill'
                ? 'w-full h-full object-cover'
                : 'max-w-full max-h-full object-contain'
              : 'w-[390px] max-h-[820px] aspect-[9/16] rounded-3xl overflow-hidden shadow-2xl border border-cyan-500/30'
          }`}
        >
          {/* Backdrop video clip / image */}
          <div className="absolute inset-0 bg-black">
            <img
              src={work.banner || work.poster}
              alt=""
              className={`w-full h-full object-cover transition-all duration-700 ${
                isPlaying ? 'opacity-40 scale-105' : 'opacity-65 scale-100'
              }`}
            />
            <div className="absolute inset-0 bg-gradient-to-t from-black via-transparent to-black/80" />
            <div className="absolute inset-0 bg-gradient-to-r from-black/80 via-transparent to-black/80" />
          </div>

          {/* Buffering Loading Indicator */}
          {isBuffering && (
            <div className="absolute inset-0 z-30 flex items-center justify-center bg-black/60 backdrop-blur-sm">
              <LoadingHUD compact message="正在缓冲 4K 流媒体分片…" subMessage="极速 CDN 节点专线连接中" />
            </div>
          )}

          {/* Floating Live Danmaku Layer */}
          {showDanmaku && (
            <div className="absolute inset-0 overflow-hidden pointer-events-none z-10">
              {danmakuList.map((d, idx) => (
                <div
                  key={d.id}
                  style={{
                    top: `${d.topPercent}%`,
                    color: d.color,
                    animationDuration: `${12 + (idx % 4) * 2}s`,
                    animationDelay: `${(idx * 2) % 8}s`
                  }}
                  className="absolute whitespace-nowrap text-sm sm:text-base font-bold drop-shadow-[0_2px_4px_rgba(0,0,0,0.9)] animate-[danmaku_14s_linear_infinite]"
                >
                  {d.text}
                </div>
              ))}
            </div>
          )}

          {/* Center Play/Pause & Seek Cluster */}
          <div
            className={`relative z-20 flex items-center justify-center gap-8 sm:gap-14 transition-opacity duration-300 ${
              showControls && !isLocked ? 'opacity-100' : 'opacity-0 pointer-events-none'
            }`}
          >
            {/* Rewind 10s */}
            <button
              onClick={e => {
                e.stopPropagation();
                setCurrentTime(Math.max(0, currentTime - 10));
              }}
              className="group flex flex-col items-center gap-1.5 p-3 rounded-full bg-black/50 hover:bg-black/75 border border-cyan-500/30 hover:border-cyan-400 backdrop-blur-md transition-all active:scale-90"
              title="快退 10 秒"
            >
              <RotateCcw className="w-6 h-6 text-slate-300 group-hover:text-cyan-400" />
              <span className="text-[10px] font-mono text-cyan-300">-10s</span>
            </button>

            {/* Glowing Big Center Play/Pause */}
            <button
              onClick={e => {
                e.stopPropagation();
                setIsPlaying(!isPlaying);
              }}
              className="w-20 h-20 sm:w-24 sm:h-24 rounded-full bg-gradient-to-r from-cyan-500 via-sky-500 to-blue-600 text-white flex items-center justify-center shadow-[0_0_40px_rgba(0,210,255,0.5)] hover:shadow-[0_0_60px_rgba(0,210,255,0.8)] hover:scale-105 active:scale-95 transition-all"
              title={isPlaying ? '暂停 (空格)' : '播放 (空格)'}
            >
              {isPlaying ? (
                <Pause className="w-10 h-10 fill-white" />
              ) : (
                <Play className="w-10 h-10 ml-1.5 fill-white" />
              )}
            </button>

            {/* Fast-forward 10s */}
            <button
              onClick={e => {
                e.stopPropagation();
                setCurrentTime(Math.min(duration, currentTime + 10));
              }}
              className="group flex flex-col items-center gap-1.5 p-3 rounded-full bg-black/50 hover:bg-black/75 border border-cyan-500/30 hover:border-cyan-400 backdrop-blur-md transition-all active:scale-90"
              title="快进 10 秒"
            >
              <RotateCw className="w-6 h-6 text-slate-300 group-hover:text-cyan-400" />
              <span className="text-[10px] font-mono text-cyan-300">+10s</span>
            </button>
          </div>
        </div>
      </div>

      {/* Lock Button (Original Cyan Blue Style) */}
      <button
        onClick={e => {
          e.stopPropagation();
          setIsLocked(!isLocked);
          setShowControls(true);
        }}
        className={`fixed left-6 top-1/2 -translate-y-1/2 z-40 p-3 rounded-full border backdrop-blur-md transition-all ${
          isLocked
            ? 'bg-cyan-500/30 text-cyan-300 border-cyan-500/50 shadow-lg shadow-cyan-500/20'
            : showControls
            ? 'bg-black/50 text-slate-400 border-white/10 hover:text-white hover:border-cyan-500/50'
            : 'opacity-0 pointer-events-none'
        }`}
        title={isLocked ? '点击解锁屏幕控制' : '点击锁定全屏控制'}
      >
        {isLocked ? <Lock className="w-5 h-5 text-cyan-400" /> : <Unlock className="w-5 h-5" />}
      </button>

      {/* ================= TOP HEADER BAR ================= */}
      <header
        className={`relative z-30 px-3 sm:px-8 py-3 sm:py-4 bg-gradient-to-b from-black/95 via-black/70 to-transparent transition-all duration-300 ${
          showControls && !isLocked ? 'translate-y-0 opacity-100' : '-translate-y-full opacity-0 pointer-events-none'
        }`}
      >
        {!isLandscape ? (
          // 📱 Portrait Mode: Clean 2-tier spacious layout
          <div className="flex flex-col gap-2">
            <div className="flex items-center justify-between">
              <button
                onClick={onClose}
                className="flex items-center gap-1.5 px-3 py-1.5 rounded-full bg-white/10 hover:bg-cyan-500/20 border border-white/10 hover:border-cyan-500/40 text-xs font-semibold text-white transition-all active:scale-95 shadow-sm"
              >
                <X className="w-4 h-4 text-cyan-400" />
                <span>退出最大化 (Esc)</span>
              </button>

              <div className="flex items-center gap-2 select-none">
                <button
                  onClick={() => setIsLandscape(true)}
                  className="flex items-center gap-1 px-3 py-1.5 rounded-full bg-cyan-500/20 hover:bg-cyan-500/30 border border-cyan-500/40 text-xs font-semibold text-cyan-300 transition-all active:scale-95 shadow-sm"
                  title="切换横屏模式"
                >
                  <Rotate3d className="w-3.5 h-3.5 text-cyan-400" />
                  <span>🔄 旋转横屏</span>
                </button>
                <div className="flex items-center gap-1 text-[11px] text-slate-300 font-mono bg-white/5 border border-white/10 px-2.5 py-1 rounded-full">
                  <Clock className="w-3 h-3 text-cyan-400" />
                  <span>{currentTimeStr}</span>
                </div>
              </div>
            </div>

            <div className="flex items-center gap-2 px-1">
              <h2 className="text-sm font-extrabold text-white truncate max-w-[220px] drop-shadow-md">
                {work.title}
              </h2>
              <span className="px-2 py-0.5 rounded-full bg-cyan-500/20 text-cyan-300 text-[10px] font-mono font-bold border border-cyan-500/30 flex-shrink-0">
                {work.mainType === 'movie' ? '正片' : `第 ${currentEpisode < 10 ? '0' + currentEpisode : currentEpisode} 集`}
              </span>
              <span className="text-[10px] text-slate-400 truncate hidden xs:inline">
                · {work.category} · ★ {work.score}
              </span>
            </div>
          </div>
        ) : (
          // 🔄 Landscape Mode: Single spacious wide row
          <div className="flex items-center justify-between">
            {/* Left: Back & Title info */}
            <div className="flex items-center gap-4 min-w-0">
              <button
                onClick={onClose}
                className="flex items-center gap-1.5 px-3 py-1.5 rounded-full bg-white/10 hover:bg-cyan-500/20 border border-white/10 hover:border-cyan-500/40 text-xs sm:text-sm font-semibold text-white transition-all hover:scale-105 active:scale-95"
              >
                <X className="w-4 h-4 text-cyan-400" />
                <span>退出最大化 (Esc)</span>
              </button>

              <div className="truncate">
                <div className="flex items-center gap-2">
                  <h2 className="text-base sm:text-lg font-extrabold text-white truncate">
                    {work.title}
                  </h2>
                  <span className="px-2 py-0.5 rounded-full bg-cyan-500/20 text-cyan-300 text-xs font-mono font-bold border border-cyan-500/30">
                    {work.mainType === 'movie' ? '正片' : `第 ${currentEpisode < 10 ? '0' + currentEpisode : currentEpisode} 集`}
                  </span>
                </div>
                <div className="hidden sm:flex items-center gap-2 text-xs text-slate-400 mt-0.5">
                  <span>{work.category}</span>
                  <span>·</span>
                  <span>{work.year}</span>
                  <span>·</span>
                  <span className="text-cyan-300 font-bold">★ {work.score}</span>
                  <span>·</span>
                  <span className="text-cyan-400">极速专线 {line + 1}</span>
                </div>
              </div>
            </div>

            {/* Right: Orientation Switcher, Status Badges, Clock */}
            <div className="flex items-center gap-3 select-none">
              <button
                onClick={() => setIsLandscape(prev => !prev)}
                className="flex items-center gap-1.5 px-3 py-1.5 rounded-full bg-gradient-to-r from-cyan-500/20 to-blue-600/20 hover:from-cyan-500/30 hover:to-blue-600/30 border border-cyan-500/40 text-xs font-semibold text-cyan-300 transition-all hover:scale-105"
                title="点击切换横屏与竖屏模式"
              >
                <Smartphone className="w-3.5 h-3.5" />
                <span>📱 切换竖屏</span>
              </button>

              <div className="hidden md:flex items-center gap-2">
                <span className="px-2.5 py-1 rounded-full bg-cyan-500/15 border border-cyan-500/30 text-cyan-300 text-xs font-semibold flex items-center gap-1">
                  <ShieldCheck className="w-3.5 h-3.5" />
                  <span>防盗链专线直连</span>
                </span>
                <span className="px-2.5 py-1 rounded-full bg-cyan-500/15 border border-cyan-500/30 text-cyan-300 text-xs font-semibold font-mono">
                  60 FPS
                </span>
              </div>

              <div className="flex items-center gap-1.5 text-xs text-slate-300 font-mono bg-white/5 border border-white/10 px-3 py-1.5 rounded-full">
                <Clock className="w-3.5 h-3.5 text-cyan-400" />
                <span>{currentTimeStr}</span>
              </div>
            </div>
          </div>
        )}
      </header>

      {/* ================= BOTTOM CONTROLLER BAR ================= */}
      <footer
        className={`relative z-30 px-4 sm:px-8 py-4 bg-gradient-to-t from-black/95 via-black/75 to-transparent space-y-3 transition-all duration-300 ${
          showControls && !isLocked ? 'translate-y-0 opacity-100' : 'translate-y-full opacity-0 pointer-events-none'
        }`}
      >
        {/* Interactive Scrub Progress Bar */}
        <div className="space-y-1.5">
          <div
            onClick={e => {
              const rect = e.currentTarget.getBoundingClientRect();
              const pos = Math.max(0, Math.min(1, (e.clientX - rect.left) / rect.width));
              setCurrentTime(Math.floor(pos * duration));
            }}
            onMouseMove={e => {
              const rect = e.currentTarget.getBoundingClientRect();
              const pos = Math.max(0, Math.min(1, (e.clientX - rect.left) / rect.width));
              setHoverTime(Math.floor(pos * duration));
            }}
            onMouseLeave={() => setHoverTime(null)}
            className="relative w-full h-2.5 hover:h-4 bg-white/15 rounded-full cursor-pointer group transition-all"
          >
            <div
              className="absolute inset-y-0 left-0 bg-white/20 rounded-full"
              style={{ width: `${Math.min(100, (currentTime / duration) * 100 + 15)}%` }}
            />
            <div
              className="absolute inset-y-0 left-0 bg-gradient-to-r from-cyan-400 via-sky-400 to-blue-500 rounded-full relative"
              style={{ width: `${(currentTime / duration) * 100}%` }}
            >
              <div className="absolute right-0 top-1/2 -translate-y-1/2 w-4 h-4 rounded-full bg-white shadow-[0_0_12px_rgba(0,210,255,0.9)] scale-0 group-hover:scale-100 transition-transform" />
            </div>

            {hoverTime !== null && (
              <div
                style={{ left: `${(hoverTime / duration) * 100}%` }}
                className="absolute -top-7 -translate-x-1/2 px-2 py-0.5 rounded bg-black/90 text-cyan-300 text-[10px] font-mono border border-cyan-500/40 pointer-events-none"
              >
                {formatTime(hoverTime)}
              </div>
            )}
          </div>

          <div className="flex items-center justify-between text-xs font-mono text-slate-400 px-1">
            <span className="text-white font-bold">{formatTime(currentTime)}</span>
            <span>{formatTime(duration)}</span>
          </div>
        </div>

        {/* Action Controls Toolbar */}
        <div className="flex flex-wrap items-center justify-between gap-3 pt-1">
          {/* Left Buttons: Play, Next, Volume */}
          <div className="flex items-center gap-3">
            <button
              onClick={() => setIsPlaying(!isPlaying)}
              className="w-10 h-10 rounded-full bg-gradient-to-r from-cyan-500 to-blue-600 text-white flex items-center justify-center transition-all hover:scale-105 active:scale-95 shadow-md shadow-cyan-500/30"
              title={isPlaying ? '暂停' : '播放'}
            >
              {isPlaying ? <Pause className="w-5 h-5 fill-white" /> : <Play className="w-5 h-5 ml-0.5 fill-white" />}
            </button>

            {work.mainType !== 'movie' && (
              <button
                onClick={() => {
                  if (currentEpisode < totalEpisodes) {
                    onEpisodeChange(currentEpisode + 1);
                    setCurrentTime(0);
                  }
                }}
                disabled={currentEpisode >= totalEpisodes}
                className="flex items-center gap-1.5 px-3 py-2 rounded-xl bg-white/5 hover:bg-cyan-500/20 border border-white/10 hover:border-cyan-500/30 text-xs font-semibold text-slate-200 transition-all disabled:opacity-40"
                title="播放下一集"
              >
                <SkipForward className="w-4 h-4 text-cyan-400" />
                <span className="hidden sm:inline">下一集</span>
              </button>
            )}

            {/* Volume Control */}
            <div className="flex items-center gap-2 bg-white/5 border border-white/10 px-3 py-1.5 rounded-xl">
              <button
                onClick={() => setIsMuted(!isMuted)}
                className="text-slate-300 hover:text-cyan-400"
              >
                {isMuted || volume === 0 ? <VolumeX className="w-4 h-4" /> : <Volume2 className="w-4 h-4" />}
              </button>
              <input
                type="range"
                min="0"
                max="1"
                step="0.05"
                value={isMuted ? 0 : volume}
                onChange={e => {
                  setVolume(parseFloat(e.target.value));
                  setIsMuted(false);
                }}
                className="w-16 sm:w-24 accent-cyan-400 cursor-pointer h-1.5 bg-white/20 rounded-lg"
              />
            </div>
          </div>

          {/* Center Danmaku Input Box */}
          <form
            onSubmit={handleSendDanmaku}
            className="hidden lg:flex items-center gap-2 bg-white/5 border border-white/10 rounded-full px-3 py-1 focus-within:border-cyan-500/60 max-w-sm flex-1"
          >
            <input
              type="text"
              value={danmakuInput}
              onChange={e => setDanmakuInput(e.target.value)}
              placeholder="发条弹幕互动一下吧…"
              className="bg-transparent border-none text-xs text-white placeholder-slate-500 outline-none flex-1"
            />
            <button
              type="submit"
              className="text-cyan-400 hover:text-cyan-300 p-1 text-xs flex items-center gap-1"
            >
              <Send className="w-3.5 h-3.5" />
            </button>
          </form>

          {/* Right Buttons: Danmaku, Speed, Quality, Line, Episodes Drawer, Orientation */}
          <div className="flex items-center gap-2 sm:gap-2.5">
            {/* Danmaku Toggle */}
            <button
              onClick={() => setShowDanmaku(!showDanmaku)}
              className={`flex items-center gap-1 px-2.5 py-1.5 rounded-lg text-xs font-semibold border transition-all ${
                showDanmaku
                  ? 'bg-cyan-500/20 text-cyan-300 border-cyan-500/40'
                  : 'bg-white/5 text-slate-400 border-white/10'
              }`}
            >
              <MessageSquare className="w-3.5 h-3.5" />
              <span>弹幕</span>
            </button>

            {/* Speed Selector */}
            <div className="relative group">
              <button className="px-2.5 py-1.5 rounded-lg bg-white/5 border border-white/10 text-xs font-mono font-semibold text-slate-200 hover:text-white flex items-center gap-1">
                <span>{speed}</span>
              </button>
              <div className="absolute bottom-full right-0 mb-2 hidden group-hover:flex flex-col bg-slate-900/95 border border-cyan-500/30 rounded-xl p-1 shadow-2xl backdrop-blur-xl z-50">
                {['0.5x', '0.75x', '1.0x', '1.25x', '1.5x', '2.0x'].map(s => (
                  <button
                    key={s}
                    onClick={() => setSpeed(s)}
                    className={`px-3 py-1.5 text-xs text-left font-mono rounded-lg transition-all ${
                      speed === s ? 'bg-cyan-500/20 text-cyan-300 font-bold' : 'text-slate-300 hover:bg-white/10'
                    }`}
                  >
                    {s}
                  </button>
                ))}
              </div>
            </div>

            {/* Line Switcher */}
            <div className="relative group">
              <button className="px-2.5 py-1.5 rounded-lg bg-white/5 border border-white/10 text-xs font-semibold text-slate-200 hover:text-white flex items-center gap-1">
                <Radio className="w-3.5 h-3.5 text-cyan-400" />
                <span>线路{line + 1}</span>
              </button>
              <div className="absolute bottom-full right-0 mb-2 hidden group-hover:flex flex-col bg-slate-900/95 border border-cyan-500/30 rounded-xl p-1 shadow-2xl backdrop-blur-xl z-50 whitespace-nowrap">
                {['极速直连专线 1', '备用超清专线 2', '高速海外专线 3'].map((l, i) => (
                  <button
                    key={l}
                    onClick={() => setLine(i)}
                    className={`px-3 py-1.5 text-xs text-left rounded-lg transition-all ${
                      line === i ? 'bg-cyan-500/20 text-cyan-300 font-bold' : 'text-slate-300 hover:bg-white/10'
                    }`}
                  >
                    {l}
                  </button>
                ))}
              </div>
            </div>

            {/* Orientation Button in Bottom Bar */}
            <button
              onClick={() => setIsLandscape(prev => !prev)}
              className="px-2.5 py-1.5 rounded-lg bg-cyan-500/20 border border-cyan-500/40 text-xs font-semibold text-cyan-300 hover:bg-cyan-500/30 flex items-center gap-1"
              title="切换横竖屏"
            >
              <Rotate3d className="w-3.5 h-3.5" />
              <span className="hidden sm:inline">{isLandscape ? '竖屏' : '横屏'}</span>
            </button>

            {/* Episode Quick Drawer Button */}
            {work.mainType !== 'movie' && (
              <button
                onClick={() => setShowEpisodeDrawer(!showEpisodeDrawer)}
                className={`flex items-center gap-1 px-3 py-1.5 rounded-lg text-xs font-bold border transition-all ${
                  showEpisodeDrawer
                    ? 'bg-gradient-to-r from-cyan-500 to-blue-600 text-white border-transparent shadow-lg shadow-cyan-500/25'
                    : 'bg-white/10 text-white border-white/10 hover:bg-cyan-500/20'
                }`}
              >
                <Layers className="w-3.5 h-3.5 text-cyan-400" />
                <span>选集 ({totalEpisodes})</span>
              </button>
            )}

            {/* Exit Maximize Button */}
            <button
              onClick={onClose}
              className="p-2 rounded-lg bg-white/10 hover:bg-cyan-500/20 border border-white/10 text-white transition-all hover:scale-105 active:scale-95"
              title="退出最大化播放"
            >
              <Minimize2 className="w-4 h-4 text-cyan-400" />
            </button>
          </div>
        </div>
      </footer>

      {/* ================= EPISODES QUICK DRAWER ================= */}
      {showEpisodeDrawer && (
        <div
          onClick={e => e.stopPropagation()}
          className="fixed top-0 right-0 bottom-0 w-80 sm:w-96 z-50 bg-[#0a0e17]/95 border-l border-cyan-500/20 p-5 shadow-2xl backdrop-blur-2xl flex flex-col justify-between animate-[slideIn_0.25s_ease-out]"
        >
          <div className="space-y-4">
            <div className="flex items-center justify-between pb-3 border-b border-white/10">
              <div className="flex items-center gap-2">
                <Layers className="w-4 h-4 text-cyan-400" />
                <h3 className="font-bold text-white text-sm">剧集列表</h3>
                <span className="text-xs text-slate-400">共 {totalEpisodes} 集</span>
              </div>
              <button
                onClick={() => setShowEpisodeDrawer(false)}
                className="p-1.5 rounded-lg text-slate-400 hover:text-white hover:bg-white/10"
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            <p className="text-xs text-slate-400">
              点击下方集数即可免刷新极速切换播放线路：
            </p>

            <div className="grid grid-cols-4 sm:grid-cols-5 gap-2 max-h-[68vh] overflow-y-auto pr-1">
              {epArray.map(ep => {
                const isCur = ep === currentEpisode;
                return (
                  <button
                    key={ep}
                    onClick={() => {
                      onEpisodeChange(ep);
                      setCurrentTime(0);
                      setIsPlaying(true);
                    }}
                    className={`py-2 rounded-xl text-center text-xs font-mono font-bold border transition-all ${
                      isCur
                        ? 'bg-gradient-to-r from-cyan-500 to-blue-600 text-white border-transparent shadow-lg shadow-cyan-500/30 scale-105'
                        : 'bg-white/5 text-slate-300 border-white/10 hover:bg-cyan-500/15 hover:border-cyan-500/40'
                    }`}
                  >
                    {ep < 10 ? `0${ep}` : ep}
                  </button>
                );
              })}
            </div>
          </div>

          <div className="pt-3 border-t border-white/10 text-center text-[11px] text-cyan-400">
            支持 4K 蓝光流媒体原生无损分发
          </div>
        </div>
      )}
    </div>
  );
};
