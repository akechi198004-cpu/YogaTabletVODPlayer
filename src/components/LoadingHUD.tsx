import React from 'react';
import { Film, Radio, Cpu, Sparkles } from 'lucide-react';

interface LoadingHUDProps {
  message?: string;
  subMessage?: string;
  className?: string;
  compact?: boolean;
}

export const LoadingHUD: React.FC<LoadingHUDProps> = ({
  message = '正在极速解析云端播放线路…',
  subMessage = '正在直连 4K 蓝光流媒体节点 · 防盗链标头自动注入',
  className = '',
  compact = false
}) => {
  return (
    <div
      className={`flex flex-col items-center justify-center p-6 text-center select-none ${className}`}
    >
      {/* Floating Cyber Glass Container */}
      <div
        className={`relative overflow-hidden rounded-3xl bg-slate-950/80 border border-cyan-500/30 backdrop-blur-xl shadow-2xl shadow-cyan-500/20 flex flex-col items-center ${
          compact ? 'p-5 max-w-xs' : 'p-7 sm:p-9 max-w-sm w-full'
        }`}
      >
        {/* Subtle Ambient Radial Glow */}
        <div className="absolute -top-12 -left-12 w-32 h-32 bg-cyan-500/20 rounded-full blur-2xl pointer-events-none" />
        <div className="absolute -bottom-12 -right-12 w-32 h-32 bg-blue-500/20 rounded-full blur-2xl pointer-events-none" />

        {/* Engine Badge */}
        <div className="inline-flex items-center gap-1.5 px-3 py-1 rounded-full bg-cyan-500/15 border border-cyan-500/30 text-[10px] font-mono font-bold text-cyan-300 uppercase tracking-widest mb-5">
          <Sparkles className="w-3 h-3 text-cyan-400" />
          <span>HM-STREAM ENGINE 4.0</span>
        </div>

        {/* Glowing Dual-Ring Loader */}
        <div className="relative w-16 h-16 sm:w-20 sm:h-20 flex items-center justify-center mb-5">
          {/* Outer Neon Ring */}
          <div className="absolute inset-0 rounded-full border-2 border-transparent border-t-cyan-400 border-r-blue-500 animate-spin" />
          {/* Middle Counter-Rotating Ring */}
          <div className="absolute inset-1.5 rounded-full border-2 border-transparent border-b-sky-300 border-l-cyan-500 animate-[spin_3s_linear_infinite_reverse]" />
          {/* Glowing Center Hub */}
          <div className="w-10 h-10 sm:w-12 sm:h-12 rounded-full bg-gradient-to-br from-cyan-500 to-blue-600 flex items-center justify-center shadow-[0_0_20px_rgba(0,210,255,0.6)] animate-pulse">
            <Film className="w-5 h-5 sm:w-6 sm:h-6 text-white" />
          </div>
        </div>

        {/* Main Status Text */}
        <h4 className="text-sm sm:text-base font-extrabold text-white tracking-wide mb-1.5">
          {message}
        </h4>

        {/* Subtitle Status */}
        <p className="text-xs text-cyan-300 font-medium leading-relaxed max-w-xs mb-4">
          {subMessage}
        </p>

        {/* Equalizer Wave / Streaming Pulse Bars */}
        <div className="flex items-end gap-1.5 h-4 mb-4">
          {[40, 75, 100, 60, 85, 45, 90].map((h, i) => (
            <div
              key={i}
              style={{
                height: `${h}%`,
                animationDelay: `${i * 0.15}s`
              }}
              className="w-1 bg-gradient-to-t from-cyan-500 to-blue-400 rounded-full animate-[pulse_1s_ease-in-out_infinite]"
            />
          ))}
        </div>

        {/* Shimmer Gliding Progress Beam */}
        <div className="w-full bg-white/10 h-1.5 rounded-full overflow-hidden relative">
          <div className="absolute inset-y-0 left-0 w-2/5 bg-gradient-to-r from-transparent via-cyan-400 to-blue-500 rounded-full animate-[progress_1.8s_ease-in-out_infinite]" />
        </div>
      </div>
    </div>
  );
};
