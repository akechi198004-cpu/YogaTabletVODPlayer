import React, { useState, useEffect, useRef, useMemo } from 'react';
import {
  Download,
  Smartphone,
  Tv,
  Code2,
  CheckCircle2,
  Layers,
  ShieldCheck,
  Terminal,
  Copy,
  Check,
  Play,
  Pause,
  RotateCcw,
  RotateCw,
  Search,
  Star,
  Clock,
  Flame,
  Film,
  Sparkles,
  Cpu,
  FileArchive,
  ArrowDownToLine,
  HelpCircle,
  ExternalLink,
  ChevronRight,
  MonitorPlay,
  SlidersHorizontal,
  BookmarkCheck,
  FileCode,
  HardDrive,
  Maximize2,
  Grid3X3,
  LayoutGrid,
  List,
  Filter,
  Volume2,
  VolumeX,
  Eye,
  Radio,
  CheckCircle,
  Rotate3d,
  ArrowLeft,
  Tv2,
  RadioTower,
  KeyRound,
  Compass,
  CornerDownLeft
} from 'lucide-react';
import { KOTLIN_FILES, KotlinFile } from './data/kotlinCodeData';
import {
  MediaWork,
  CATALOG_DATABASE,
  MAIN_CATEGORY_TABS,
  SUB_CATEGORIES_BY_MAIN
} from './data/mockCatalog';
import { PosterImage } from './components/PosterImage';
import { CinemaPlayerModal } from './components/CinemaPlayerModal';
import { LoadingHUD } from './components/LoadingHUD';

// Category Multi-Dimensional Filter Data
// Category Multi-Dimensional Filter Data
const CHANNEL_TABS = [
  { key: 'all', label: '🌟 全部影视' },
  { key: 'movie', label: '🎬 电影专区' },
  { key: 'tv', label: '📺 电视剧场' },
  { key: 'anime', label: '⚡ 动漫新番' },
  { key: 'variety', label: '🎉 综艺娱乐' },
  { key: 'doc', label: '👑 纪录片' }
] as const;

const GENRES_BY_CHANNEL: Record<string, { label: string; value: string }[]> = {
  all: [
    { label: '全部类型', value: 'all' },
    { label: '动作', value: '动作' },
    { label: '科幻', value: '科幻' },
    { label: '喜剧', value: '喜剧' },
    { label: '剧情', value: '剧情' },
    { label: '悬疑', value: '悬疑' },
    { label: '爱情', value: '爱情' },
    { label: '动画', value: '动画' },
    { label: '战争', value: '战争' }
  ],
  movie: [
    { label: '全部类型', value: 'all' },
    { label: '动作片', value: '动作片' },
    { label: '科幻片', value: '科幻片' },
    { label: '喜剧片', value: '喜剧片' },
    { label: '剧情片', value: '剧情片' },
    { label: '悬疑犯罪', value: '悬疑' },
    { label: '爱情片', value: '爱情片' },
    { label: '动画片', value: '动画片' },
    { label: '战争片', value: '战争片' }
  ],
  tv: [
    { label: '全部类型', value: 'all' },
    { label: '国产剧', value: '国产剧' },
    { label: '香港剧', value: '香港剧' },
    { label: '台湾剧', value: '台湾剧' },
    { label: '欧美剧', value: '欧美剧' },
    { label: '日剧', value: '日剧' },
    { label: '韩剧', value: '韩剧' },
    { label: '古装剧', value: '古装' },
    { label: '悬疑剧', value: '悬疑' }
  ],
  anime: [
    { label: '全部类型', value: 'all' },
    { label: '日本动漫', value: '日本动漫' },
    { label: '国创动画', value: '国创' },
    { label: '热血燃番', value: '热血' },
    { label: '治愈奇幻', value: '治愈' }
  ],
  variety: [
    { label: '全部类型', value: 'all' },
    { label: '大陆真人秀', value: '真人秀' },
    { label: '脱口秀相声', value: '脱口秀' },
    { label: '音乐竞技', value: '音乐' },
    { label: '港台综艺', value: '港台' }
  ],
  doc: [
    { label: '全部类型', value: 'all' },
    { label: '自然生态', value: '自然' },
    { label: '美食风味', value: '美食' },
    { label: '人文历史', value: '历史' }
  ]
};

const FILTER_AREAS = [
  { label: '全部地区', value: 'all' },
  { label: '中国大陆', value: '中国大陆' },
  { label: '中国香港', value: '中国香港' },
  { label: '中国台湾', value: '中国台湾' },
  { label: '欧美', value: '欧美' },
  { label: '美国', value: '美国' },
  { label: '日本', value: '日本' },
  { label: '韩国', value: '韩国' }
];

const FILTER_YEARS = [
  { label: '全部年份', value: 'all' },
  { label: '2025', value: '2025' },
  { label: '2024', value: '2024' },
  { label: '2023', value: '2023' },
  { label: '2022', value: '2022' },
  { label: '2021', value: '2021' },
  { label: '更早', value: '更早' }
];

const FILTER_SORTS = [
  { label: '🔥 按热度', value: 'hot' as const },
  { label: '★ 按评分', value: 'score' as const },
  { label: '🕒 按最新', value: 'time' as const }
];

export default function App() {
  // 5 Top Level Tabs: Phone Preview, TV Preview, Kotlin Code, Build Arch, Install Tutorial
  const [activeTab, setActiveTab] = useState<'phone' | 'tv' | 'code' | 'build' | 'install'>('phone');

  // Phone Simulator Orientation
  const [phoneOrientation, setPhoneOrientation] = useState<'portrait' | 'landscape'>('portrait');

  // Code Tab state
  const [selectedFile, setSelectedFile] = useState<KotlinFile>(KOTLIN_FILES[0]);
  const [copied, setCopied] = useState(false);

  // Download state
  const [downloadStatus, setDownloadStatus] = useState<string>('');

  // --------------------------------------------------------------------------
  // SHARED APK STATE (100% ALIGNED WITH REAL KOTLIN CODE & XML LAYOUTS)
  // --------------------------------------------------------------------------

  // MainActivity Navigation Tabs: HOME, CATEGORY, TOP, DAILY, MINE
  const [navTab, setNavTab] = useState<'home' | 'category' | 'top' | 'daily' | 'mine'>('home');

  // Category Multi-Dimensional Filters
  const [mainCatType, setMainCatType] = useState<'all' | 'movie' | 'tv' | 'anime' | 'variety' | 'doc'>('all');
  const [filterGenre, setFilterGenre] = useState<string>('all');
  const [filterArea, setFilterArea] = useState<string>('all');
  const [filterYear, setFilterYear] = useState<string>('all');
  const [filterSort, setFilterSort] = useState<'hot' | 'score' | 'time'>('hot');
  // List layout style: 'grid3' (一行3个海报, 默认) | 'grid2' (一行2个大图) | 'list' (图文单列)
  const [listLayoutMode, setListLayoutMode] = useState<'grid3' | 'grid2' | 'list'>('grid3');
  const [currentCatSlug, setCurrentCatSlug] = useState<string>('dongzuopian');
  const [catPage, setCatPage] = useState<number>(1);

  // Top Tab Period: day, week, month
  const [topPeriod, setTopPeriod] = useState<'day' | 'week' | 'month'>('day');

  // Daily Tab Day: 周一 ~ 周日
  const [dailyDay, setDailyDay] = useState<string>('周一');

  // Mine Tab Subtab: fav vs history
  const [mineSubTab, setMineSubTab] = useState<'fav' | 'history'>('fav');

  // Active Android Activity: 'main' | 'detail' | 'player' | 'search'
  const [currentActivity, setCurrentActivity] = useState<'main' | 'detail' | 'player' | 'search'>('main');

  // DetailActivity state
  const [activeWork, setActiveWork] = useState<MediaWork | null>(null);
  const [activeLineId, setActiveLineId] = useState<number>(1);
  const [isDetailLoading, setIsDetailLoading] = useState<boolean>(false);

  // PlayerActivity state
  const [activeEpisode, setActiveEpisode] = useState<number>(1);
  const [isPlaying, setIsPlaying] = useState<boolean>(false);
  const [playTime, setPlayTime] = useState<number>(145);
  const [duration, setDuration] = useState<number>(2700);
  const [playbackSpeed, setPlaybackSpeed] = useState<string>('1.0x');
  const [isPlayerLoading, setIsPlayerLoading] = useState<boolean>(false);
  const [playerOverlayVisible, setPlayerOverlayVisible] = useState<boolean>(true);
  const [isCinemaMaximized, setIsCinemaMaximized] = useState<boolean>(false);

  // SearchActivity state
  const [searchKeyword, setSearchKeyword] = useState<string>('');
  const [searchHistory, setSearchHistory] = useState<string[]>(['繁花', '沙丘2', '三体', '庆余年']);

  // Storage state
  const [favorites, setFavorites] = useState<string[]>(['m1', 't1', 'a1']);
  const [watchHistory, setWatchHistory] = useState([
    { workId: 't1', title: '繁花', ep: '第01集', progress: '上次播放至 02:25', progressSec: 145 },
    { workId: 'm1', title: '沙丘2 (Dune: Part Two)', ep: '正片', progress: '上次播放至 45:10', progressSec: 2710 }
  ]);

  // --------------------------------------------------------------------------
  // TV REMOTE CONTROL D-PAD FOCUS STATE (FOR TV PREVIEW TAB)
  // --------------------------------------------------------------------------
  const [tvFocusIndex, setTvFocusIndex] = useState<number>(0);

  // Playback timer ticker
  useEffect(() => {
    let timer: any;
    if (isPlaying && currentActivity === 'player' && !isPlayerLoading) {
      timer = setInterval(() => {
        setPlayTime(t => (t >= duration ? 0 : t + 1));
      }, 1000);
    }
    return () => clearInterval(timer);
  }, [isPlaying, currentActivity, isPlayerLoading, duration]);

  // Player overlay auto hide (4s timeout matching PlayerActivity.kt)
  useEffect(() => {
    let timer: any;
    if (isPlaying && playerOverlayVisible && currentActivity === 'player') {
      timer = setTimeout(() => {
        setPlayerOverlayVisible(false);
      }, 4000);
    }
    return () => clearTimeout(timer);
  }, [isPlaying, playerOverlayVisible, currentActivity]);

  const copyCode = () => {
    navigator.clipboard.writeText(selectedFile.code);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  const handleDownload = (_filename: string) => {
    setDownloadStatus('当前页面是电视界面预览，电视 APK 需从本地 Android 构建产物获取。');
    window.setTimeout(() => setDownloadStatus(''), 5000);
  };

  // Open DetailActivity
  const openDetail = (work: MediaWork) => {
    setActiveWork(work);
    setIsDetailLoading(true);
    setCurrentActivity('detail');
    setTimeout(() => {
      setIsDetailLoading(false);
    }, 350);
  };

  // Open PlayerActivity
  const startPlayer = (work: MediaWork, episode = 1) => {
    setActiveWork(work);
    setActiveEpisode(episode);
    setIsPlayerLoading(true);
    setIsPlaying(true);
    setPlayTime(0);
    setCurrentActivity('player');

    // Record history
    setWatchHistory(prev => [
      {
        workId: work.id,
        title: work.title,
        ep: work.mainType === 'movie' ? '正片' : `第${episode < 10 ? '0' + episode : episode}集`,
        progress: '刚刚播放',
        progressSec: 0
      },
      ...prev.filter(h => h.workId !== work.id)
    ]);

    setTimeout(() => {
      setIsPlayerLoading(false);
    }, 500);
  };

  const toggleFavorite = (workId: string) => {
    if (favorites.includes(workId)) {
      setFavorites(favorites.filter(id => id !== workId));
    } else {
      setFavorites([workId, ...favorites]);
    }
  };

  const isFav = (workId: string) => favorites.includes(workId);

  const moveTvFocus = (direction: 'UP' | 'DOWN' | 'LEFT' | 'RIGHT') => {
    const root = document.getElementById('hm-tv-screen');
    const candidates = Array.from(root?.querySelectorAll<HTMLElement>('[data-tv-focusable="true"]') ?? [])
      .filter(element => element.getClientRects().length > 0);
    const active = document.activeElement as HTMLElement | null;
    const activeIndex = active ? candidates.indexOf(active) : -1;
    if (activeIndex < 0) {
      candidates[0]?.focus();
      return;
    }

    const axis = direction.toLowerCase() as 'up' | 'down' | 'left' | 'right';
    const from = candidates[activeIndex].getBoundingClientRect();
    const fromX = from.left + from.width / 2;
    const fromY = from.top + from.height / 2;
    const next = candidates
      .map((element, index) => {
        if (index === activeIndex) return null;
        const rect = element.getBoundingClientRect();
        const dx = rect.left + rect.width / 2 - fromX;
        const dy = rect.top + rect.height / 2 - fromY;
        const primary = axis === 'left' ? -dx : axis === 'right' ? dx : axis === 'up' ? -dy : dy;
        if (primary <= 1) return null;
        const cross = axis === 'left' || axis === 'right' ? Math.abs(dy) : Math.abs(dx);
        return { element, index, score: primary + cross * 1.8 };
      })
      .filter((item): item is { element: HTMLElement; index: number; score: number } => item !== null)
      .sort((a, b) => a.score - b.score)[0];

    if (next) {
      next.element.focus();
      next.element.scrollIntoView({ block: 'nearest', inline: 'nearest' });
      setTvFocusIndex(next.index);
    }
  };

  // TV Remote D-Pad Navigation Handler
  const handleTvRemoteKey = (key: 'UP' | 'DOWN' | 'LEFT' | 'RIGHT' | 'OK' | 'BACK' | 'HOME') => {
    if (key === 'HOME') {
      setCurrentActivity('main');
      setNavTab('home');
      setTvFocusIndex(0);
      return;
    }

    if (key === 'BACK') {
      if (currentActivity === 'player') setCurrentActivity('detail');
      else if (currentActivity === 'detail' || currentActivity === 'search') setCurrentActivity('main');
      return;
    }

    if (currentActivity === 'player') {
      const root = document.getElementById('hm-tv-screen');
      const candidates = Array.from(root?.querySelectorAll<HTMLElement>('[data-tv-focusable="true"]') ?? [])
        .filter(element => element.getClientRects().length > 0);
      const active = document.activeElement as HTMLElement | null;
      const hasFocusedControl = active != null && candidates.includes(active);
      if (key === 'OK') {
        if (playerOverlayVisible && hasFocusedControl) active?.click();
        else {
          setPlayerOverlayVisible(true);
          requestAnimationFrame(() => root?.querySelector<HTMLElement>('[data-tv-default-focus="true"]')?.focus());
        }
      } else if (key === 'LEFT') {
        if (playerOverlayVisible && hasFocusedControl) {
          moveTvFocus(key);
          return;
        }
        setPlayTime(Math.max(0, playTime - 10));
        setPlayerOverlayVisible(true);
      } else if (key === 'RIGHT') {
        if (playerOverlayVisible && hasFocusedControl) {
          moveTvFocus(key);
          return;
        }
        setPlayTime(Math.min(duration, playTime + 10));
        setPlayerOverlayVisible(true);
      } else if (key === 'UP' || key === 'DOWN') {
        if (playerOverlayVisible && hasFocusedControl) moveTvFocus(key);
        else {
          setPlayerOverlayVisible(true);
          requestAnimationFrame(() => root?.querySelector<HTMLElement>('[data-tv-default-focus="true"]')?.focus());
        }
      }
      return;
    }

    const root = document.getElementById('hm-tv-screen');
    const candidates = Array.from(root?.querySelectorAll<HTMLElement>('[data-tv-focusable="true"]') ?? []);
    const active = document.activeElement as HTMLElement | null;
    const activeIndex = active ? candidates.indexOf(active) : -1;

    if (key === 'OK') {
      if (activeIndex >= 0) active?.click();
      else candidates[0]?.click();
      return;
    }

    if (activeIndex < 0) {
      candidates[0]?.focus();
      return;
    }

    moveTvFocus(key);
  };

  useEffect(() => {
    if (activeTab !== 'tv') return;
    const root = document.getElementById('hm-tv-screen');
    if (!root) return;
    root.querySelectorAll<HTMLElement>('button, .cursor-pointer').forEach(element => {
      element.dataset.tvFocusable = 'true';
      if (element.tagName !== 'BUTTON' && !element.hasAttribute('tabindex')) element.tabIndex = 0;
    });
  }, [activeTab, currentActivity, navTab, mainCatType, filterGenre, topPeriod, dailyDay, mineSubTab]);

  // Keyboard remote control listener for TV tab
  useEffect(() => {
    if (activeTab !== 'tv') return;
    const onKey = (e: KeyboardEvent) => {
      // Prevent browser scroll on arrow keys when interacting with TV
      if (['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight', ' '].includes(e.key)) {
        e.preventDefault();
      }
      if (e.key === 'ArrowUp') handleTvRemoteKey('UP');
      else if (e.key === 'ArrowDown') handleTvRemoteKey('DOWN');
      else if (e.key === 'ArrowLeft') handleTvRemoteKey('LEFT');
      else if (e.key === 'ArrowRight') handleTvRemoteKey('RIGHT');
      else if (e.key === 'Enter' || e.key === ' ') {
        e.preventDefault();
        handleTvRemoteKey('OK');
      }
      else if (e.key === 'Escape' || e.key === 'Backspace') handleTvRemoteKey('BACK');
      else if (e.key === 'h' || e.key === 'H') handleTvRemoteKey('HOME');
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [activeTab, currentActivity, navTab, tvFocusIndex, isPlaying, playerOverlayVisible, playTime, duration, activeWork, mainCatType]);

  const currentSubCats = SUB_CATEGORIES_BY_MAIN[mainCatType as 'movie' | 'tv' | 'anime' | 'variety'] || [];

  const currentCategoryWorks = useMemo(() => {
    let list = CATALOG_DATABASE.filter(w => {
      // 1. Main channel (if not 'all')
      if (mainCatType !== 'all') {
        if (mainCatType === 'movie' && w.mainType !== 'movie') return false;
        if (mainCatType === 'tv' && w.mainType !== 'tv') return false;
        if (mainCatType === 'anime' && w.mainType !== 'anime') return false;
        if (mainCatType === 'variety' && w.mainType !== 'variety') return false;
        if (mainCatType === 'doc' && w.mainType !== 'doc') return false;
      }

      // 2. Subcategory / Genre
      if (filterGenre !== 'all') {
        const matchesCategory = w.category.includes(filterGenre);
        const matchesTags = w.tags && w.tags.some(t => t.includes(filterGenre));
        const matchesSlug = w.catSlug && w.catSlug.includes(filterGenre);
        const matchesTitle = w.title.includes(filterGenre);
        const matchesDesc = w.desc.includes(filterGenre);
        if (!matchesCategory && !matchesTags && !matchesSlug && !matchesTitle && !matchesDesc) return false;
      }

      // 3. Region / Area
      if (filterArea !== 'all') {
        const matchesArea =
          w.area.includes(filterArea) ||
          (filterArea === '欧美' && (w.area.includes('美国') || w.area.includes('英国') || w.area.includes('欧洲'))) ||
          (filterArea === '中国香港' && (w.area.includes('香港') || w.category.includes('香港'))) ||
          (filterArea === '中国台湾' && (w.area.includes('台湾') || w.category.includes('台湾')));
        if (!matchesArea) return false;
      }

      // 4. Release Year
      if (filterYear !== 'all') {
        if (filterYear === '更早') {
          const y = parseInt(w.year, 10);
          if (!isNaN(y) && y >= 2021) return false;
        } else if (w.year !== filterYear) {
          return false;
        }
      }

      return true;
    });

    // 5. Sorting
    if (filterSort === 'score') {
      list = [...list].sort((a, b) => parseFloat(b.score || '0') - parseFloat(a.score || '0'));
    } else if (filterSort === 'hot') {
      list = [...list].sort((a, b) => (b.hot || 0) - (a.hot || 0));
    } else if (filterSort === 'time') {
      list = [...list].sort((a, b) => parseInt(b.year || '0', 10) - parseInt(a.year || '0', 10));
    }

    return list;
  }, [mainCatType, filterGenre, filterArea, filterYear, filterSort]);

  // Render Poster Card in 2-3 columns grid (User's preferred style)
  function renderPosterCard(item: MediaWork) {
    return (
      <div
        key={item.id}
        onClick={() => openDetail(item)}
        className="group flex flex-col rounded-xl bg-[#0d111a] border border-white/5 hover:border-cyan-500/50 hover:bg-[#111726] transition-all cursor-pointer overflow-hidden p-1.5 shadow-md active:scale-95 select-none"
      >
        <div className="relative aspect-[2/3] w-full rounded-lg overflow-hidden bg-[#121620]">
          <PosterImage
            src={item.poster}
            alt={item.title}
            className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300"
          />
          {item.score && item.score !== '0.0' && (
            <span className="absolute top-1 right-1 px-1.5 py-0.5 rounded bg-black/80 backdrop-blur-md text-cyan-300 font-bold text-[9px] sm:text-[10px] border border-cyan-500/30 shadow-sm">
              ★ {item.score}
            </span>
          )}
          {item.progress && (
            <span className="absolute bottom-1 left-1 right-1 truncate px-1 py-0.5 rounded bg-black/85 backdrop-blur-md text-white font-medium text-[9px] border border-white/10 text-center">
              {item.progress}
            </span>
          )}
        </div>
        <div className="pt-2 pb-0.5 px-0.5 min-w-0 space-y-0.5">
          <h4 className="font-bold text-xs text-white truncate group-hover:text-cyan-300 transition-colors">
            {item.title}
          </h4>
          <div className="text-[10px] text-slate-400 truncate flex items-center justify-between">
            <span>{item.category}</span>
            <span className="font-mono text-slate-500">{item.year}</span>
          </div>
        </div>
      </div>
    );
  }

  const heroWork = CATALOG_DATABASE.find(w => w.banner) || CATALOG_DATABASE[0];

  return (
    <div className="min-h-screen bg-[#07090e] text-slate-100 flex flex-col font-sans selection:bg-cyan-500/30">
      {/* Top Header */}
      <header className="border-b border-cyan-500/20 bg-[#0d111a]/95 backdrop-blur-md sticky top-0 z-40">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 h-16 flex items-center justify-between">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-cyan-400 to-blue-600 flex items-center justify-center shadow-lg shadow-cyan-500/25">
              <Film className="w-5 h-5 text-white" />
            </div>
            <div>
              <div className="flex items-center gap-2">
                <span className="font-extrabold text-lg text-white">HM 影视 原生 Android 客户端</span>
                <span className="text-[11px] font-bold px-2 py-0.5 rounded-full bg-cyan-500/15 text-cyan-300 border border-cyan-500/30">
                  电视版界面预览
                </span>
              </div>
              <p className="text-xs text-slate-400 hidden sm:block">
                网页交互预览不代表 APK 已发布；原生电视版需从 Android 工程构建
              </p>
            </div>
          </div>

          <div className="flex items-center gap-2.5">
            <button
              onClick={() => handleDownload('HMVideo-release.apk')}
              className="inline-flex items-center gap-2 px-4 py-2 rounded-xl bg-gradient-to-r from-cyan-500 to-blue-600 text-white font-semibold text-xs sm:text-sm shadow-lg shadow-cyan-500/25 hover:shadow-cyan-500/40 hover:scale-[1.02] active:scale-[0.98] transition-all disabled:opacity-50"
            >
              <Download className="w-4 h-4" />
              <span>查看构建状态</span>
            </button>
          </div>
          {downloadStatus && <span role="status" aria-live="polite" className="text-xs text-amber-300">{downloadStatus}</span>}
        </div>
      </header>

      {/* Main Content Area */}
      <main className="flex-1 max-w-7xl w-full mx-auto px-4 sm:px-6 py-5 space-y-6">
        {/* Latest Build Status Banner */}
        <div className="p-4 rounded-2xl bg-gradient-to-r from-cyan-950/40 via-blue-950/30 to-purple-950/20 border border-cyan-500/30 flex flex-col md:flex-row items-start md:items-center justify-between gap-4">
          <div className="flex items-start gap-3">
            <div className="w-10 h-10 rounded-xl bg-cyan-500/20 border border-cyan-500/40 flex items-center justify-center text-cyan-400 shrink-0 mt-0.5">
              <CheckCircle2 className="w-5 h-5" />
            </div>
              <div className="space-y-1">
              <div className="flex items-center gap-2 flex-wrap">
                <span className="font-bold text-white text-sm">电视端改造正在开发，网页内容仅作界面预览</span>
                <span className="text-[11px] font-mono px-2 py-0.5 rounded bg-emerald-500/20 text-emerald-300 border border-emerald-500/40 font-semibold">
                  网页预览 · 不可安装
                </span>
                <span className="text-[11px] px-2 py-0.5 rounded bg-cyan-500/20 text-cyan-300 border border-cyan-500/30">
                  原生工程仍需在本地构建并验证
                </span>
              </div>
              <p className="text-xs text-slate-300 leading-relaxed">
                原生工程使用 Android Media3 播放器。此预览没有连接真实视频源，也不能代表电视实机播放结果。<br />
                当前电视 APK 包名为 com.hm.video.letv，最低要求 Android 6.0（API 23）。它与旧版 com.hm.video 并行安装，旧版收藏和历史不会自动迁移。
              </p>
            </div>
          </div>
          <div className="flex items-center gap-2 shrink-0 self-end md:self-center">
            <button
              onClick={() => handleDownload('HMVideo-release.apk')}
              className="inline-flex items-center gap-1.5 px-3.5 py-2 rounded-xl bg-cyan-500 hover:bg-cyan-400 text-black font-bold text-xs shadow-md transition-all disabled:opacity-50"
            >
              <Download className="w-4 h-4" />
              <span>查看构建状态</span>
            </button>
          </div>
        </div>

        {/* Top 5 Primary Tabs: Phone Preview, TV Preview, Code, Build, Install */}
        <div className="flex items-center gap-2 border-b border-white/10 pb-3 overflow-x-auto scrollbar-none">
          {[
            { key: 'phone', label: '📱 手机端界面预览', desc: '触屏与全屏横竖屏' },
            { key: 'tv', label: '📺 电视端界面预览', desc: '16:9 大屏与遥控器' },
            { key: 'code', label: '💻 Kotlin 源码解析', desc: '对齐 APK 架构' },
            { key: 'build', label: '⚙️ Android 构建', desc: 'Gradle · API 23+' },
            { key: 'install', label: '📦 电视与手机安装', desc: 'U盘与远程推送' }
          ].map(tab => {
            const active = activeTab === tab.key;
            return (
              <button
                key={tab.key}
                onClick={() => setActiveTab(tab.key as any)}
                className={`flex flex-col items-start px-4 py-2 rounded-xl text-left transition-all ${
                  active
                    ? 'bg-gradient-to-r from-cyan-500 to-blue-600 text-white shadow-lg shadow-cyan-500/20'
                    : 'bg-[#0d111a] text-slate-400 hover:text-white hover:bg-white/5 border border-white/5'
                }`}
              >
                <span className="font-bold text-xs sm:text-sm">{tab.label}</span>
                <span className={`text-[10px] ${active ? 'text-cyan-100' : 'text-slate-500'}`}>
                  {tab.desc}
                </span>
              </button>
            );
          })}
        </div>

        {/* ========================================================================= */}
        {/* TAB 1: 📱 手机端界面预览 */}
        {/* ========================================================================= */}
        {activeTab === 'phone' && (
          <div className="grid grid-cols-1 lg:grid-cols-12 gap-8 items-start">
            {/* Phone Screen Container */}
            <div
              className={`transition-all duration-500 flex flex-col items-center mx-auto ${
                phoneOrientation === 'landscape' ? 'lg:col-span-12 w-full max-w-[880px]' : 'lg:col-span-5 w-[380px]'
              }`}
            >
              {/* Phone Orientation Switcher Floating Pill */}
              <div className="flex items-center gap-2 mb-3 select-none">
                <button
                  onClick={() =>
                    setPhoneOrientation(prev => (prev === 'portrait' ? 'landscape' : 'portrait'))
                  }
                  className="px-4 py-1.5 rounded-full bg-cyan-500/15 border border-cyan-500/30 text-cyan-300 text-xs font-semibold flex items-center gap-2 hover:bg-cyan-500/25 transition-all shadow-lg shadow-cyan-500/10 hover:scale-105"
                  title="点击切换模拟器纵横屏显示"
                >
                  <Rotate3d className="w-3.5 h-3.5 text-cyan-400" />
                  <span>
                    {phoneOrientation === 'landscape' ? '📱 恢复手机竖屏模式' : '🔄 旋转手机横屏模式'}
                  </span>
                </button>
              </div>

              {/* Phone Mockup Frame */}
              <div
                className={`transition-all duration-500 rounded-[50px] bg-black p-3.5 shadow-2xl shadow-cyan-500/20 border-4 border-slate-700 relative flex flex-col overflow-hidden ${
                  phoneOrientation === 'landscape'
                    ? 'w-full max-w-[880px] h-[480px]'
                    : 'w-[360px] sm:w-[380px] h-[780px]'
                }`}
              >
                {/* Phone Notch */}
                <div className="absolute top-4 left-1/2 -translate-x-1/2 w-28 h-3.5 bg-slate-900 rounded-full z-30 flex items-center justify-center">
                  <div className="w-2.5 h-2.5 rounded-full bg-slate-950 mr-2 border border-slate-800" />
                  <div className="w-8 h-1 bg-slate-800 rounded-full" />
                </div>

                {/* Inner Screen */}
                <div className="flex-1 bg-[#07090e] rounded-[38px] overflow-hidden flex flex-col relative pt-6">
                  {/* Status Bar */}
                  <div className="px-5 py-1 flex items-center justify-between text-[10px] text-slate-400 font-mono select-none">
                    <span>12:45</span>
                    <div className="flex items-center gap-1.5">
                      <span>5G</span>
                      <span>100%</span>
                    </div>
                  </div>

                  {/* Android Screen Body */}
                  <div className="flex-1 flex flex-col overflow-hidden">
                    {renderPhoneActivityScreen()}
                  </div>
                </div>
              </div>
            </div>

            {/* Phone Specifications & Details Panel (Right Side) */}
            {phoneOrientation === 'portrait' && (
              <div className="lg:col-span-7 space-y-4">
                <div className="p-5 rounded-2xl bg-[#0d111a] border border-cyan-500/20 space-y-3 shadow-xl">
                  <div className="flex items-center justify-between">
                    <div className="flex items-center gap-2">
                      <Smartphone className="w-4 h-4 text-cyan-400" />
                      <h3 className="text-sm font-bold text-white">手机端 APK 原生界面架构说明</h3>
                    </div>
                    <span className="text-[10px] text-cyan-300 font-mono bg-cyan-500/10 px-2 py-0.5 rounded border border-cyan-500/30">
                      100% 原生 Activity 映射
                    </span>
                  </div>

                  <p className="text-xs text-slate-300 leading-relaxed">
                    当前模拟器完全遵照 <code>android_app</code> 原生工程中的 Kotlin 逻辑与 XML 布局渲染：
                  </p>

                  <div className="grid grid-cols-1 sm:grid-cols-2 gap-3 text-xs">
                    <div className="p-3 rounded-xl bg-white/5 border border-white/5 space-y-1">
                      <div className="font-bold text-cyan-300">MainActivity 5大导航</div>
                      <div className="text-slate-400 text-[11px]">
                        首页（今日主打+热播列表）、分类（电影/剧集/动漫/综艺二重过滤）、榜单、更新、收藏。
                      </div>
                    </div>

                    <div className="p-3 rounded-xl bg-white/5 border border-white/5 space-y-1">
                      <div className="font-bold text-cyan-300">PlayerActivity 影院播放器</div>
                      <div className="text-slate-400 text-[11px]">
                        支持实时手势快进退、进度拖拽、多清晰度专线切换、倍速（0.75x~2.0x）与横竖屏旋转。
                      </div>
                    </div>

                    <div className="p-3 rounded-xl bg-white/5 border border-white/5 space-y-1">
                      <div className="font-bold text-cyan-300">DetailActivity 详情与选集</div>
                      <div className="text-slate-400 text-[11px]">
                        110×150dp 电影海报、剧情简介、多线路播放源、4列剧集网格选集与收藏状态存储。
                      </div>
                    </div>

                    <div className="p-3 rounded-xl bg-white/5 border border-white/5 space-y-1">
                      <div className="font-bold text-cyan-300">SearchActivity 全网搜索</div>
                      <div className="text-slate-400 text-[11px]">
                        带热门影视热搜词汇排行榜、本地历史记录追踪与模糊搜索结果卡片呈现。
                      </div>
                    </div>
                  </div>

                  <div className="pt-2 flex items-center gap-3">
                    <button
                      onClick={() => handleDownload('HMVideo-release.apk')}
                      className="px-4 py-2 rounded-xl bg-gradient-to-r from-cyan-500 to-blue-600 text-white font-bold text-xs shadow-lg shadow-cyan-500/20"
                    >
                    查看 Android 构建说明
                    </button>
                    <span className="text-xs text-slate-400">该网页没有提供可安装 APK</span>
                  </div>
                </div>
              </div>
            )}
          </div>
        )}

        {/* ========================================================================= */}
        {/* TAB 2: 📺 电视端界面预览 */}
        {/* ========================================================================= */}
        {activeTab === 'tv' && (
          <div className="grid grid-cols-1 xl:grid-cols-12 gap-8 items-start">
            {/* TV Screen (16:9 Widescreen) */}
            <div className="xl:col-span-8 flex flex-col items-center w-full space-y-3">
              {/* TV Frame Container */}
              <div className="w-full relative rounded-[28px] bg-[#0c101b] border-4 border-slate-700 p-3.5 shadow-2xl shadow-cyan-500/15">
                {/* TV Header Info Bar */}
                <div className="flex items-center justify-between px-3 pb-2 text-[11px] text-slate-400 border-b border-white/5 font-mono">
                  <div className="flex items-center gap-2">
                    <span className="w-2.5 h-2.5 rounded-full bg-cyan-400 animate-pulse" />
                    <span className="font-bold text-white">Android TV 4K · 智能电视模式</span>
                    <span className="text-[10px] px-1.5 py-0.2 rounded bg-cyan-500/20 text-cyan-300 border border-cyan-500/30">
                      D-Pad 遥控器驱动
                    </span>
                  </div>
                  <div className="flex items-center gap-3">
                    <span>WiFi: 已连接</span>
                    <span>19:30</span>
                  </div>
                </div>

                {/* TV Screen Body (16:9 Aspect Ratio) */}
                <div className="relative aspect-[16/9] w-full bg-[#07090e] rounded-xl overflow-hidden flex flex-col">
                  {renderTvActivityScreen()}
                </div>

                {/* TV Stand Base */}
                <div className="flex justify-center pt-2.5">
                  <div className="px-4 py-0.5 rounded bg-slate-800 text-[10px] text-slate-400 font-bold tracking-widest uppercase">
                    HM-TV SMART BOX · 遥控器已连接
                  </div>
                </div>
              </div>

              {/* TV Preview Notice */}
              <div className="p-3 rounded-xl bg-white/5 border border-white/5 w-full text-xs text-slate-400 flex items-center justify-between">
                <span>💡 提示：可直接点击右侧虚拟遥控器的方向键与 OK 键测试电视操作</span>
                <span className="text-cyan-300 font-mono">仅为网页预览，未连接电视实机</span>
              </div>
            </div>

            {/* Virtual TV Remote Controller & TV Specs (Right Side) */}
            <div className="xl:col-span-4 space-y-5">
              {/* Virtual TV Remote Controller */}
              <div className="p-5 rounded-2xl bg-[#0d111a] border border-cyan-500/30 space-y-4 shadow-xl">
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-2">
                    <Compass className="w-4 h-4 text-cyan-400" />
                    <h3 className="text-xs font-bold text-white">电视机顶盒遥控器操作模拟</h3>
                  </div>
                  <span className="text-[10px] text-cyan-300 font-bold bg-cyan-500/15 px-2 py-0.5 rounded border border-cyan-500/30">
                    遥控器就绪
                  </span>
                </div>
                <p className="text-[11px] text-slate-400 leading-relaxed">
                  在低版本 Android 电视机顶盒上无触摸屏，用户使用红外/蓝牙遥控器操作。
                  点击下方按键观察屏幕上的 <span className="text-cyan-300 font-semibold">青蓝双层聚焦高亮框</span> 移动：
                </p>

                {/* D-Pad Buttons Cluster */}
                <div className="flex flex-col items-center justify-center py-3 select-none">
                  <button
                    onClick={() => handleTvRemoteKey('UP')}
                    className="w-14 h-11 rounded-t-2xl bg-slate-800 hover:bg-cyan-500/30 text-white font-bold flex items-center justify-center border border-white/10 active:scale-95 shadow-md"
                    title="遥控器：向上 (UP)"
                  >
                    ▲
                  </button>
                  <div className="flex items-center gap-2">
                    <button
                      onClick={() => handleTvRemoteKey('LEFT')}
                      className="w-11 h-14 rounded-l-2xl bg-slate-800 hover:bg-cyan-500/30 text-white font-bold flex items-center justify-center border border-white/10 active:scale-95 shadow-md"
                      title="遥控器：向左 (LEFT)"
                    >
                      ◀
                    </button>
                    <button
                      onClick={() => handleTvRemoteKey('OK')}
                      className="w-16 h-14 rounded-xl bg-gradient-to-r from-cyan-500 to-blue-600 text-white font-extrabold text-sm flex items-center justify-center shadow-lg shadow-cyan-500/30 active:scale-95"
                      title="遥控器：确认键 (OK / ENTER)"
                    >
                      OK
                    </button>
                    <button
                      onClick={() => handleTvRemoteKey('RIGHT')}
                      className="w-11 h-14 rounded-r-2xl bg-slate-800 hover:bg-cyan-500/30 text-white font-bold flex items-center justify-center border border-white/10 active:scale-95 shadow-md"
                      title="遥控器：向右 (RIGHT)"
                    >
                      ▶
                    </button>
                  </div>
                  <button
                    onClick={() => handleTvRemoteKey('DOWN')}
                    className="w-14 h-11 rounded-b-2xl bg-slate-800 hover:bg-cyan-500/30 text-white font-bold flex items-center justify-center border border-white/10 active:scale-95 shadow-md"
                    title="遥控器：向下 (DOWN)"
                  >
                    ▼
                  </button>
                </div>

                {/* Action Buttons */}
                <div className="grid grid-cols-2 gap-2 pt-2 border-t border-white/5">
                  <button
                    onClick={() => handleTvRemoteKey('BACK')}
                    className="py-2.5 rounded-xl bg-white/5 hover:bg-white/10 text-xs text-slate-300 font-semibold border border-white/10 flex items-center justify-center gap-1.5 active:scale-95"
                  >
                    <ArrowLeft className="w-3.5 h-3.5" />
                    <span>返回键 (BACK)</span>
                  </button>
                  <button
                    onClick={() => handleTvRemoteKey('HOME')}
                    className="py-2.5 rounded-xl bg-white/5 hover:bg-white/10 text-xs text-slate-300 font-semibold border border-white/10 flex items-center justify-center gap-1.5 active:scale-95"
                  >
                    <span>主页 (HOME)</span>
                  </button>
                </div>
              </div>

              {/* TV Specific Architecture Checklist */}
              <div className="p-4 rounded-2xl bg-[#0d111a] border border-white/10 space-y-2.5 text-xs">
                <span className="font-bold text-white flex items-center gap-1.5">
                  <CheckCircle2 className="w-4 h-4 text-emerald-400" />
                  <span>电视界面预览信息</span>
                </span>
                <div className="space-y-1.5 text-slate-300">
                  <div className="flex justify-between py-1 border-b border-white/5">
                    <span className="text-slate-400">最低 Android 版本：</span>
                    <span className="text-cyan-300 font-semibold">Android 6.0 (API 23)</span>
                  </div>
                  <div className="flex justify-between py-1 border-b border-white/5">
                    <span className="text-slate-400">免触控特性：</span>
                    <span className="text-emerald-400 font-semibold">touchscreen: false</span>
                  </div>
                  <div className="flex justify-between py-1 border-b border-white/5">
                    <span className="text-slate-400">TV 桌面图标直显：</span>
                    <span className="text-emerald-400 font-semibold">LEANBACK_LAUNCHER</span>
                  </div>
                  <div className="flex justify-between py-1">
                    <span className="text-slate-400">遥控器焦点高亮：</span>
                    <span className="text-cyan-300 font-semibold">state_focused: 2.5dp 光环</span>
                  </div>
                </div>

                <div className="pt-2">
                  <button
                    onClick={() => handleDownload('HMVideo-release.apk')}
                    className="w-full py-2.5 rounded-xl bg-gradient-to-r from-cyan-500 to-blue-600 text-white font-bold text-xs shadow-md shadow-cyan-500/20"
                  >
                    查看 Android 构建说明
                  </button>
                </div>
              </div>
            </div>
          </div>
        )}

        {/* ========================================================================= */}
        {/* TAB 3: 💻 KOTLIN 源码解析 (KOTLIN SOURCE CODE VIEWER) */}
        {/* ========================================================================= */}
        {activeTab === 'code' && (
          <div className="grid grid-cols-1 lg:grid-cols-12 gap-6 items-start">
            <div className="lg:col-span-4 space-y-2 bg-[#0d111a] p-3 rounded-2xl border border-white/10">
              <div className="text-xs font-bold text-white px-2 py-1 mb-1">Android Kotlin 源码快照</div>
              {KOTLIN_FILES.map(file => (
                <button
                  key={file.path}
                  onClick={() => setSelectedFile(file)}
                  className={`w-full text-left p-2.5 rounded-xl text-xs transition-all flex flex-col gap-0.5 ${
                    selectedFile.path === file.path
                      ? 'bg-cyan-500/20 text-cyan-300 border border-cyan-500/40 font-semibold'
                      : 'text-slate-400 hover:text-white hover:bg-white/5'
                  }`}
                >
                  <div className="font-mono font-bold text-white truncate">{file.name}</div>
                  <div className="text-[10px] text-slate-400 truncate">{file.path}</div>
                </button>
              ))}
            </div>

            <div className="lg:col-span-8 bg-[#0d111a] rounded-2xl border border-white/10 overflow-hidden flex flex-col">
              <div className="px-4 py-3 bg-slate-900/80 border-b border-white/5 flex items-center justify-between">
                <span className="font-mono text-xs text-cyan-300 font-semibold">{selectedFile.path}</span>
                <button
                  onClick={copyCode}
                  className="px-2.5 py-1 rounded-lg bg-white/5 border border-white/10 text-xs text-slate-300 hover:text-white flex items-center gap-1.5"
                >
                  {copied ? <Check className="w-3.5 h-3.5 text-emerald-400" /> : <Copy className="w-3.5 h-3.5" />}
                  <span>{copied ? '已复制' : '复制代码'}</span>
                </button>
              </div>
              <pre className="p-4 text-xs font-mono text-slate-300 overflow-x-auto max-h-[600px] leading-relaxed">
                <code>{selectedFile.code}</code>
              </pre>
            </div>
          </div>
        )}

        {/* ========================================================================= */}
        {/* TAB 4: ⚙️ 架构与编译说明 (BUILD ARCHITECTURE) */}
        {/* ========================================================================= */}
        {activeTab === 'build' && (
          <div className="space-y-6">
            <div className="grid grid-cols-1 md:grid-cols-3 gap-5">
              <div className="p-5 rounded-2xl bg-[#0d111a] border border-white/10 space-y-2">
                <h3 className="text-sm font-bold text-white flex items-center gap-2">
                  <Tv className="w-4 h-4 text-cyan-400" />
                  <span>电视盒子免触控与遥控器</span>
                </h3>
                <p className="text-xs text-slate-400 leading-relaxed">
                  声明 <code>touchscreen: false</code> 与 <code>leanback: false</code>，并加入
                  <code>LEANBACK_LAUNCHER</code> 意图。让低版本电视机顶盒（如天猫魔盒、外贸盒子）在开机后直接在应用桌面展示图标。
                </p>
              </div>

              <div className="p-5 rounded-2xl bg-[#0d111a] border border-white/10 space-y-2">
                <h3 className="text-sm font-bold text-white flex items-center gap-2">
                  <ShieldCheck className="w-4 h-4 text-cyan-400" />
                  <span>电视网络连接策略</span>
                </h3>
                <p className="text-xs text-slate-400 leading-relaxed">
                  网络连接使用系统证书和主机名验证。旧电视若证书链过旧，需采集设备日志后针对具体站点处理。
                </p>
              </div>

              <div className="p-5 rounded-2xl bg-[#0d111a] border border-white/10 space-y-2">
                <h3 className="text-sm font-bold text-white flex items-center gap-2">
                  <Cpu className="w-4 h-4 text-cyan-400" />
                  <span>D8 & AAPT2 静态链接</span>
                </h3>
                <p className="text-xs text-slate-400 leading-relaxed">
                  Android 模块通过 Gradle 处理 Media3、传递依赖和资源。电视版本最低为 API 23，构建产物位于 app/build/outputs/apk。
                </p>
              </div>
            </div>

            <div className="p-6 rounded-2xl bg-[#0d111a] border border-cyan-500/20 space-y-4">
              <h3 className="text-sm font-bold text-white">统一的 Android 构建入口</h3>
              <pre className="p-4 rounded-xl bg-black/60 text-xs font-mono text-cyan-300 border border-white/5 overflow-x-auto leading-relaxed">
{`# Windows：在项目目录执行
powershell -ExecutionPolicy Bypass -File .\\build-letv.ps1

# Linux：在 hm-video 目录执行（需要本地 Gradle 和 Android SDK）
./build_apk.sh

# 电视 APK：android_app/app/build/outputs/apk/debug/app-debug.apk`}
              </pre>
            </div>
          </div>
        )}

        {/* ========================================================================= */}
        {/* TAB 5: 📦 电视与手机安装教程 (INSTALLATION TUTORIAL) */}
        {/* ========================================================================= */}
        {activeTab === 'install' && (
          <div className="p-6 rounded-2xl bg-[#0d111a] border border-white/10 space-y-6">
            <h3 className="text-base font-bold text-white">Android 智能电视与电视盒子安装指南</h3>
            <div className="grid grid-cols-1 md:grid-cols-2 gap-6 text-xs text-slate-300">
              <div className="space-y-3 p-4 rounded-xl bg-white/5 border border-white/5">
                <h4 className="font-bold text-cyan-300 text-sm">方法一：U盘安装（最推荐）</h4>
                <ol className="list-decimal list-inside space-y-2 leading-relaxed text-slate-300">
                  <li>先在本地 Android 工程构建电视 APK，再将生成的 APK 复制到 U 盘。</li>
                  <li>将 U盘插入智能电视或机顶盒的 USB 接口。</li>
                  <li>在电视上打开「文件管理器」或「多媒体中心」，找到 U盘并点击 APK。</li>
                  <li>若电视提示「禁止安装未知来源应用」，前往电视设置开启「允许安装未知来源应用」后继续安装。</li>
                  <li>安装完成后，桌面将直接显示「HM 影视」图标，使用遥控器方向键即可操作！</li>
                </ol>
              </div>

              <div className="space-y-3 p-4 rounded-xl bg-white/5 border border-white/5">
                <h4 className="font-bold text-cyan-300 text-sm">方法二：局域网远程快传</h4>
                <ol className="list-decimal list-inside space-y-2 leading-relaxed text-slate-300">
                  <li>在电视上安装「当贝助手」或「小白文件管理器」。</li>
                  <li>打开其中的「远程安装」或「局域网传输」，获取电视局域网 IP 地址。</li>
                  <li>在电脑或手机浏览器中访问该地址，将构建生成的 APK 上传推送至电视。</li>
                  <li>电视端确认安装即可。</li>
                </ol>
              </div>
            </div>
          </div>
        )}
      </main>

      {/* Maximized Cinema Modal (if triggered) */}
      {isCinemaMaximized && activeWork && (
        <CinemaPlayerModal
          work={activeWork}
          currentEpisode={activeEpisode}
          isOpen={isCinemaMaximized}
          onClose={() => setIsCinemaMaximized(false)}
          onEpisodeChange={(ep: number) => {
            setActiveEpisode(ep);
            setPlayTime(0);
          }}
          isPlaying={isPlaying}
          setIsPlaying={setIsPlaying}
          currentTime={playTime}
          setCurrentTime={setPlayTime}
          duration={duration}
        />
      )}
    </div>
  );

  // --------------------------------------------------------------------------
  // PHONE ACTIVITY SCREEN RENDERER (100% REAL APK LAYOUT)
  // --------------------------------------------------------------------------
  function renderPhoneActivityScreen() {
    if (currentActivity === 'detail' && activeWork) {
      return renderPhoneDetailScreen(activeWork);
    }
    if (currentActivity === 'player' && activeWork) {
      return renderPhonePlayerScreen(activeWork);
    }
    if (currentActivity === 'search') {
      return renderPhoneSearchScreen();
    }
    return renderPhoneMainScreen();
  }

  // Phone MainActivity (activity_main.xml)
  function renderPhoneMainScreen() {
    return (
      <div className="flex-1 flex flex-col bg-[#07090e] overflow-hidden text-xs">
        {/* Top App Bar (R.id.top_bar, 56dp) */}
        <div className="h-14 px-4 bg-[#0d111a] border-b border-white/5 flex items-center justify-between select-none">
          <div className="flex items-center gap-2">
            <span className="font-extrabold text-base text-white">HM 影视</span>
            <span className="text-[10px] px-1.5 py-0.2 rounded bg-cyan-500/20 text-cyan-300 border border-cyan-500/30">
              v2.4.0 TV
            </span>
          </div>

          <button
            onClick={() => setCurrentActivity('search')}
            className="px-3.5 py-1.5 rounded-full bg-[#121620] border border-white/10 text-xs text-slate-400 hover:text-white flex items-center gap-1.5 transition-all"
          >
            <Search className="w-3.5 h-3.5 text-cyan-400" />
            <span> 🔍 搜索影视 </span>
          </button>
        </div>

        {/* Content Frame */}
        <div className="flex-1 overflow-y-auto p-3 space-y-4">
          {navTab === 'home' && (
            <div className="space-y-4">
              {/* Hero Banner Card (createHeroCard) */}
              <div
                onClick={() => openDetail(heroWork)}
                className="p-3 rounded-xl bg-[#0d111a] border border-white/10 flex gap-3 cursor-pointer hover:border-cyan-500/40 transition-all shadow-md"
              >
                <div className="w-[110px] h-[150px] rounded-lg overflow-hidden flex-shrink-0 bg-[#121620]">
                  <PosterImage src={heroWork.poster} alt={heroWork.title} className="w-full h-full object-cover" />
                </div>
                <div className="flex-1 flex flex-col justify-between py-1 min-w-0">
                  <div className="space-y-1">
                    <span className="inline-block text-[10px] font-bold px-2 py-0.5 rounded-full bg-gradient-to-r from-cyan-400 to-blue-600 text-white">
                      今日热播主打
                    </span>
                    <h3 className="font-bold text-sm text-white truncate pt-1">{heroWork.title}</h3>
                    <div className="text-[11px] text-cyan-400 font-medium">
                      {heroWork.category} · {heroWork.year} · ★ {heroWork.score}
                    </div>
                    <p className="text-[11px] text-slate-400 line-clamp-2 leading-relaxed">
                      {heroWork.desc}
                    </p>
                  </div>
                  <button
                    onClick={e => {
                      e.stopPropagation();
                      startPlayer(heroWork, 1);
                    }}
                    className="self-start px-3.5 py-1.5 rounded-full bg-gradient-to-r from-cyan-500 to-blue-600 text-white font-bold text-xs shadow-md shadow-cyan-500/20 flex items-center gap-1 active:scale-95"
                  >
                    <Play className="w-3.5 h-3.5 fill-white" />
                    <span>▶ 立即播放</span>
                  </button>
                </div>
              </div>

              {/* Section Heading & Grid View Switcher */}
              <div className="text-xs font-bold text-white flex items-center justify-between pt-1">
                <span className="flex items-center gap-1.5">
                  <span>🔥 最近更新热播（含电影、剧集、动漫）</span>
                </span>
                {/* Single Pure Icon Layout Switcher - Zero Text */}
                <button
                  onClick={() => setListLayoutMode(prev => (prev === 'grid3' ? 'list' : 'grid3'))}
                  className="p-1.5 rounded-lg bg-[#121620] border border-white/10 text-cyan-400 hover:text-white transition-all select-none"
                >
                  {listLayoutMode === 'grid3' ? (
                    <Grid3X3 className="w-3.5 h-3.5" />
                  ) : (
                    <List className="w-3.5 h-3.5" />
                  )}
                </button>
              </div>

              {/* Media Cards: Default 3 posters per row grid (User loved style) */}
              {listLayoutMode === 'grid3' && (
                <div className="grid grid-cols-3 gap-2">
                  {CATALOG_DATABASE.map(item => renderPosterCard(item))}
                </div>
              )}
              {listLayoutMode === 'grid2' && (
                <div className="grid grid-cols-2 gap-2.5">
                  {CATALOG_DATABASE.map(item => renderPosterCard(item))}
                </div>
              )}
              {listLayoutMode === 'list' && (
                <div className="space-y-2.5">
                  {CATALOG_DATABASE.map(item => (
                    <div
                      key={item.id}
                      onClick={() => openDetail(item)}
                      className="p-2.5 rounded-xl bg-[#0d111a] border border-white/5 hover:border-cyan-500/40 transition-all flex items-center gap-3 cursor-pointer"
                    >
                      <div className="w-[75px] h-[105px] rounded-lg overflow-hidden flex-shrink-0 bg-[#121620]">
                        <PosterImage src={item.poster} alt={item.title} className="w-full h-full object-cover" />
                      </div>
                      <div className="flex-1 min-w-0 space-y-1">
                        <h4 className="font-bold text-sm text-white truncate">{item.title}</h4>
                        <div className="text-[11px] text-slate-400">{item.category} · {item.year} · {item.area}</div>
                        <div className="text-[11px] text-cyan-400 font-medium">{item.progress}</div>
                        <div className="text-[11px] font-bold text-cyan-300">★ 评分 {item.score}</div>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}

          {navTab === 'category' && (
            <div className="space-y-3.5">
              {/* Category Filter Controls Section */}
              <div className="p-3 rounded-2xl bg-[#0d111a] border border-white/10 space-y-2.5 shadow-md">
                {/* Row 1: Primary Category Tabs (电影 / 电视剧 / 动漫 / 综艺 / 纪录片) */}
                <div>
                  <div className="text-[10px] text-slate-400 font-medium mb-1.5 flex items-center justify-between">
                    <span>频道分类</span>
                    <span className="text-cyan-400 font-mono text-[9px]">支持多维动态筛选</span>
                  </div>
                  <div className="flex items-center gap-1.5 overflow-x-auto pb-1 scrollbar-none">
                    {CHANNEL_TABS.map(cat => {
                      const isCur = mainCatType === cat.key;
                      return (
                        <button
                          key={cat.key}
                          onClick={() => {
                            setMainCatType(cat.key as any);
                            setFilterGenre('all');
                          }}
                          className={`px-3 py-1.5 rounded-full text-xs font-semibold whitespace-nowrap transition-all ${
                            isCur
                              ? 'bg-gradient-to-r from-cyan-500 to-blue-600 text-white font-bold shadow-md shadow-cyan-500/25'
                              : 'bg-[#121620] text-slate-400 hover:text-white border border-white/5'
                          }`}
                        >
                          {cat.label}
                        </button>
                      );
                    })}
                  </div>
                </div>

                {/* Row 2: Sub-category / Genre Pills */}
                <div>
                  <div className="text-[10px] text-slate-400 font-medium mb-1.5">
                    类型细分 ({CHANNEL_TABS.find(c => c.key === mainCatType)?.label || '当前'})
                  </div>
                  <div className="flex items-center gap-1.5 overflow-x-auto pb-1 scrollbar-none">
                    {(GENRES_BY_CHANNEL[mainCatType] || [{ label: '全部', value: 'all' }]).map(sub => {
                      const isCur = filterGenre === sub.value;
                      return (
                        <button
                          key={sub.value}
                          onClick={() => setFilterGenre(sub.value)}
                          className={`px-2.5 py-1 rounded-full text-[11px] whitespace-nowrap transition-all ${
                            isCur
                              ? 'bg-gradient-to-r from-cyan-500 to-blue-600 text-white font-semibold shadow-sm'
                              : 'bg-[#121620] text-slate-400 hover:text-white border border-white/5'
                          }`}
                        >
                          {sub.label}
                        </button>
                      );
                    })}
                  </div>
                </div>

                {/* Row 3: Region / Area Pills */}
                <div>
                  <div className="text-[10px] text-slate-400 font-medium mb-1.5">制片地区</div>
                  <div className="flex items-center gap-1.5 overflow-x-auto pb-1 scrollbar-none">
                    {FILTER_AREAS.map(ar => {
                      const isCur = filterArea === ar.value;
                      return (
                        <button
                          key={ar.value}
                          onClick={() => setFilterArea(ar.value)}
                          className={`px-2.5 py-1 rounded-full text-[11px] whitespace-nowrap transition-all ${
                            isCur
                              ? 'bg-cyan-500/25 text-cyan-300 border border-cyan-400/50 font-semibold'
                              : 'bg-[#121620] text-slate-400 hover:text-white border border-white/5'
                          }`}
                        >
                          {ar.label}
                        </button>
                      );
                    })}
                  </div>
                </div>

                {/* Row 4: Release Year Pills */}
                <div>
                  <div className="text-[10px] text-slate-400 font-medium mb-1.5">上映年份</div>
                  <div className="flex items-center gap-1.5 overflow-x-auto pb-1 scrollbar-none">
                    {FILTER_YEARS.map(yr => {
                      const isCur = filterYear === yr.value;
                      return (
                        <button
                          key={yr.value}
                          onClick={() => setFilterYear(yr.value)}
                          className={`px-2.5 py-1 rounded-full text-[11px] whitespace-nowrap transition-all ${
                            isCur
                              ? 'bg-cyan-500/25 text-cyan-300 border border-cyan-400/50 font-semibold'
                              : 'bg-[#121620] text-slate-400 hover:text-white border border-white/5'
                          }`}
                        >
                          {yr.label}
                        </button>
                      );
                    })}
                  </div>
                </div>

                {/* Row 5: Sort Options & Reset Button */}
                <div className="flex items-center justify-between pt-1 border-t border-white/5">
                  <div className="flex items-center gap-1.5">
                    {FILTER_SORTS.map(s => {
                      const isCur = filterSort === s.value;
                      return (
                        <button
                          key={s.value}
                          onClick={() => setFilterSort(s.value)}
                          className={`px-2.5 py-1 rounded-md text-[11px] transition-all ${
                            isCur
                              ? 'bg-white/15 text-cyan-300 font-bold border border-cyan-500/40'
                              : 'text-slate-400 hover:text-white'
                          }`}
                        >
                          {s.label}
                        </button>
                      );
                    })}
                  </div>

                  {(filterGenre !== 'all' || filterArea !== 'all' || filterYear !== 'all') && (
                    <button
                      onClick={() => {
                        setFilterGenre('all');
                        setFilterArea('all');
                        setFilterYear('all');
                      }}
                      className="text-[10px] text-slate-400 hover:text-red-400 flex items-center gap-1 underline underline-offset-2"
                    >
                      <RotateCcw className="w-3 h-3" />
                      <span>重置筛选</span>
                    </button>
                  )}
                </div>

                {/* Active Filter Summary Badges */}
                <div className="flex items-center justify-between text-[11px] text-slate-300 bg-[#07090e]/80 p-2.5 rounded-xl border border-white/5 flex-wrap gap-2">
                  <div className="flex items-center gap-1.5 flex-wrap">
                    <span className="text-cyan-400 font-bold">当前已选：</span>
                    <span className="px-2 py-0.5 rounded-full bg-cyan-500/20 text-cyan-300 border border-cyan-500/30 text-[10px] font-medium">
                      {CHANNEL_TABS.find(c => c.key === mainCatType)?.label}
                    </span>
                    <span className="px-2 py-0.5 rounded-full bg-white/5 text-slate-200 border border-white/10 text-[10px]">
                      {filterGenre === 'all' ? '全部类型' : filterGenre}
                    </span>
                    <span className="px-2 py-0.5 rounded-full bg-white/5 text-slate-200 border border-white/10 text-[10px]">
                      {filterArea === 'all' ? '全部地区' : filterArea}
                    </span>
                    <span className="px-2 py-0.5 rounded-full bg-white/5 text-slate-200 border border-white/10 text-[10px]">
                      {filterYear === 'all' ? '全部年份' : filterYear}
                    </span>
                    <span className="px-2 py-0.5 rounded-full bg-white/5 text-slate-300 border border-white/10 text-[10px]">
                      {FILTER_SORTS.find(s => s.value === filterSort)?.label}
                    </span>
                  </div>
                  {(filterGenre !== 'all' || filterArea !== 'all' || filterYear !== 'all') && (
                    <button
                      onClick={() => {
                        setFilterGenre('all');
                        setFilterArea('all');
                        setFilterYear('all');
                      }}
                      className="text-xs text-red-400 hover:text-red-300 flex items-center gap-1 font-semibold underline underline-offset-2 ml-auto"
                    >
                      <RotateCcw className="w-3 h-3" />
                      <span>清空筛选条件</span>
                    </button>
                  )}
                </div>
              </div>

              {/* Filter Results Status & Grid Layout Toggle */}
              <div className="flex items-center justify-between px-1">
                <div className="text-xs text-slate-300 flex items-center gap-1.5">
                  <span className="font-bold text-white">筛选结果</span>
                  <span className="px-1.5 py-0.2 rounded bg-cyan-500/20 text-cyan-300 text-[10px] font-mono">
                    共 {currentCategoryWorks.length} 部
                  </span>
                </div>

                {/* Single Pure Icon Layout Switcher - Zero Text */}
                <button
                  onClick={() => setListLayoutMode(prev => (prev === 'grid3' ? 'list' : 'grid3'))}
                  className="p-1.5 rounded-lg bg-[#121620] border border-white/10 text-cyan-400 hover:text-white transition-all select-none"
                >
                  {listLayoutMode === 'grid3' ? (
                    <Grid3X3 className="w-3.5 h-3.5" />
                  ) : (
                    <List className="w-3.5 h-3.5" />
                  )}
                </button>
              </div>

              {/* Works List / Grid in Category Tab */}
              {currentCategoryWorks.length > 0 ? (
                listLayoutMode === 'grid3' ? (
                  <div className="grid grid-cols-3 gap-2">
                    {currentCategoryWorks.map(item => renderPosterCard(item))}
                  </div>
                ) : listLayoutMode === 'grid2' ? (
                  <div className="grid grid-cols-2 gap-2.5">
                    {currentCategoryWorks.map(item => renderPosterCard(item))}
                  </div>
                ) : (
                  <div className="space-y-2.5">
                    {currentCategoryWorks.map(item => (
                      <div
                        key={item.id}
                        onClick={() => openDetail(item)}
                        className="p-2.5 rounded-xl bg-[#0d111a] border border-white/5 hover:border-cyan-500/40 transition-all flex items-center gap-3 cursor-pointer"
                      >
                        <div className="w-[75px] h-[105px] rounded-lg overflow-hidden flex-shrink-0 bg-[#121620]">
                          <PosterImage src={item.poster} alt={item.title} className="w-full h-full object-cover" />
                        </div>
                        <div className="flex-1 min-w-0 space-y-1">
                          <h4 className="font-bold text-sm text-white truncate">{item.title}</h4>
                          <div className="text-[11px] text-slate-400">{item.category} · {item.year} · {item.area}</div>
                          <div className="text-[11px] text-cyan-400 font-medium">{item.progress}</div>
                          <div className="text-[11px] font-bold text-cyan-300">★ 评分 {item.score}</div>
                        </div>
                      </div>
                    ))}
                  </div>
                )
              ) : (
                <div className="py-8 text-center space-y-3 bg-[#0d111a] rounded-2xl border border-white/5 p-4">
                  <p className="text-slate-300 text-xs">当前筛选组合暂无影视，已为您智能推荐本频道的精彩作品：</p>
                  <button
                    onClick={() => {
                      setFilterGenre('all');
                      setFilterArea('all');
                      setFilterYear('all');
                    }}
                    className="px-3.5 py-1.5 rounded-full bg-cyan-500/20 text-cyan-300 border border-cyan-500/30 text-xs font-semibold hover:bg-cyan-500/30 transition-all"
                  >
                    重置所有筛选参数
                  </button>
                  <div className="grid grid-cols-3 gap-2 pt-2">
                    {CATALOG_DATABASE.filter(w => mainCatType === 'all' || w.mainType === mainCatType).slice(0, 6).map(item => renderPosterCard(item))}
                  </div>
                </div>
              )}
            </div>
          )}

          {navTab === 'top' && (
            <div className="space-y-3">
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-1.5">
                  {[
                    { key: 'day', label: '🔥 今日热播' },
                    { key: 'week', label: '⭐ 本周强档' },
                    { key: 'month', label: '🏆 月度风云' }
                  ].map(p => (
                    <button
                      key={p.key}
                      onClick={() => setTopPeriod(p.key as any)}
                      className={`px-3 py-1.5 rounded-full text-xs transition-all ${
                        topPeriod === p.key
                          ? 'bg-gradient-to-r from-cyan-500 to-blue-600 text-white font-bold shadow-md shadow-cyan-500/20'
                          : 'bg-[#121620] text-slate-400 border border-white/5 hover:text-white'
                      }`}
                    >
                      {p.label}
                    </button>
                  ))}
                </div>
                <div className="flex items-center gap-2">
                  {/* Single Pure Icon Layout Switcher - Zero Text */}
                  <button
                    onClick={() => setListLayoutMode(prev => (prev === 'grid3' ? 'list' : 'grid3'))}
                    className="p-1.5 rounded-lg bg-[#121620] border border-white/10 text-cyan-400 hover:text-white transition-all select-none"
                  >
                    {listLayoutMode === 'grid3' ? (
                      <Grid3X3 className="w-3.5 h-3.5" />
                    ) : (
                      <List className="w-3.5 h-3.5" />
                    )}
                  </button>
                </div>
              </div>

              {listLayoutMode === 'grid3' ? (
                <div className="grid grid-cols-3 gap-2">
                  {CATALOG_DATABASE.slice(0, 9).map((item, idx) => (
                    <div key={item.id} className="relative">
                      <span
                        className={`absolute top-2 left-2 z-10 w-5 h-5 rounded-md flex items-center justify-center font-bold text-[10px] shadow-lg ${
                          idx === 0
                            ? 'bg-gradient-to-br from-amber-300 to-amber-500 text-black shadow-amber-500/50'
                            : idx === 1
                            ? 'bg-gradient-to-br from-slate-200 to-slate-400 text-black'
                            : idx === 2
                            ? 'bg-gradient-to-br from-amber-600 to-amber-800 text-white'
                            : 'bg-black/75 backdrop-blur-md text-slate-300 border border-white/10'
                        }`}
                      >
                        {idx + 1}
                      </span>
                      {renderPosterCard(item)}
                    </div>
                  ))}
                </div>
              ) : (
                <div className="space-y-2.5">
                  {CATALOG_DATABASE.slice(0, 8).map((item, idx) => (
                    <div
                      key={item.id}
                      onClick={() => openDetail(item)}
                      className="p-2.5 rounded-xl bg-[#0d111a] border border-white/5 flex items-center gap-3 cursor-pointer"
                    >
                      <div
                        className={`w-6 h-6 rounded-md flex items-center justify-center font-bold text-xs flex-shrink-0 ${
                          idx < 3
                            ? 'bg-gradient-to-br from-cyan-400 to-blue-600 text-white shadow-md shadow-cyan-500/30'
                            : 'bg-slate-800 text-slate-400'
                        }`}
                      >
                        {idx + 1}
                      </div>
                      <div className="w-[60px] h-[85px] rounded-lg overflow-hidden flex-shrink-0 bg-[#121620]">
                        <PosterImage src={item.poster} alt={item.title} className="w-full h-full object-cover" />
                      </div>
                      <div className="flex-1 min-w-0 space-y-1">
                        <h4 className="font-bold text-sm text-white truncate">{item.title}</h4>
                        <div className="text-[11px] text-slate-400">{item.category} · {item.progress}</div>
                        <div className="text-[11px] text-cyan-300 font-semibold">★ 评分 {item.score}</div>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}

          {navTab === 'daily' && (
            <div className="space-y-3">
              <div className="flex items-center gap-1.5 overflow-x-auto pb-1 scrollbar-none">
                {['周一', '周二', '周三', '周四', '周五', '周六', '周日'].map(day => (
                  <button
                    key={day}
                    onClick={() => setDailyDay(day)}
                    className={`px-3 py-1.5 rounded-full text-xs whitespace-nowrap transition-all ${
                      dailyDay === day
                        ? 'bg-gradient-to-r from-cyan-500 to-blue-600 text-white font-bold shadow-md shadow-cyan-500/20'
                        : 'bg-[#121620] text-slate-400 border border-white/5 hover:text-white'
                    }`}
                  >
                    {day}
                  </button>
                ))}
              </div>

              {listLayoutMode === 'grid3' ? (
                <div className="grid grid-cols-3 gap-2">
                  {CATALOG_DATABASE.filter(w => w.mainType === 'tv' || w.mainType === 'anime').map(item =>
                    renderPosterCard(item)
                  )}
                </div>
              ) : (
                <div className="space-y-2.5">
                  {CATALOG_DATABASE.filter(w => w.mainType === 'tv' || w.mainType === 'anime').map(item => (
                    <div
                      key={item.id}
                      onClick={() => openDetail(item)}
                      className="p-2.5 rounded-xl bg-[#0d111a] border border-white/5 flex items-center gap-3 cursor-pointer"
                    >
                      <div className="w-[70px] h-[95px] rounded-lg overflow-hidden flex-shrink-0 bg-[#121620]">
                        <PosterImage src={item.poster} alt={item.title} className="w-full h-full object-cover" />
                      </div>
                      <div className="flex-1 min-w-0 space-y-1">
                        <h4 className="font-bold text-sm text-white truncate">{item.title}</h4>
                        <div className="text-[11px] text-cyan-400 font-medium">{item.progress} · 实时连载</div>
                        <div className="text-[11px] text-slate-400">{item.category} · {item.year}</div>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}

          {navTab === 'mine' && (
            <div className="space-y-3">
              <div className="flex items-center gap-2">
                <button
                  onClick={() => setMineSubTab('fav')}
                  className={`px-3 py-1.5 rounded-full text-xs transition-all ${
                    mineSubTab === 'fav'
                      ? 'bg-gradient-to-r from-cyan-500 to-blue-600 text-white font-bold'
                      : 'bg-[#121620] text-slate-400 border border-white/5'
                  }`}
                >
                  ★ 我的收藏 ({favorites.length}部)
                </button>
                <button
                  onClick={() => setMineSubTab('history')}
                  className={`px-3 py-1.5 rounded-full text-xs transition-all ${
                    mineSubTab === 'history'
                      ? 'bg-gradient-to-r from-cyan-500 to-blue-600 text-white font-bold'
                      : 'bg-[#121620] text-slate-400 border border-white/5'
                  }`}
                >
                  🕒 观看历史 ({watchHistory.length}条)
                </button>
              </div>
              <div className="space-y-2.5">
                {mineSubTab === 'fav' ? (
                  CATALOG_DATABASE.filter(w => favorites.includes(w.id)).map(item => (
                    <div
                      key={item.id}
                      onClick={() => openDetail(item)}
                      className="p-2.5 rounded-xl bg-[#0d111a] border border-white/5 flex items-center justify-between cursor-pointer"
                    >
                      <div className="flex items-center gap-3 min-w-0">
                        <div className="w-[60px] h-[85px] rounded-lg overflow-hidden flex-shrink-0 bg-[#121620]">
                          <PosterImage src={item.poster} alt={item.title} className="w-full h-full object-cover" />
                        </div>
                        <div className="min-w-0 space-y-1">
                          <h4 className="font-bold text-sm text-white truncate">{item.title}</h4>
                          <div className="text-[11px] text-slate-400">{item.category} · {item.progress}</div>
                          <div className="text-[11px] text-cyan-300 font-semibold">★ {item.score}</div>
                        </div>
                      </div>
                      <button
                        onClick={e => {
                          e.stopPropagation();
                          toggleFavorite(item.id);
                        }}
                        className="p-2 rounded-lg bg-white/5 text-cyan-300 hover:text-red-400 text-xs"
                      >
                        取消收藏
                      </button>
                    </div>
                  ))
                ) : (
                  watchHistory.map(hist => {
                    const match = CATALOG_DATABASE.find(w => w.id === hist.workId) || CATALOG_DATABASE[0];
                    return (
                      <div
                        key={hist.workId}
                        onClick={() => startPlayer(match, 1)}
                        className="p-2.5 rounded-xl bg-[#0d111a] border border-white/5 flex items-center justify-between cursor-pointer"
                      >
                        <div className="flex items-center gap-3 min-w-0">
                          <div className="w-[60px] h-[85px] rounded-lg overflow-hidden flex-shrink-0 bg-[#121620]">
                            <PosterImage src={match.poster} alt={hist.title} className="w-full h-full object-cover" />
                          </div>
                          <div className="min-w-0 space-y-1">
                            <h4 className="font-bold text-sm text-white truncate">{hist.title}</h4>
                            <div className="text-[11px] text-cyan-400">{hist.ep} · {hist.progress}</div>
                            <div className="text-[10px] text-slate-400">已自动记忆进度</div>
                          </div>
                        </div>
                        <button className="px-3 py-1 rounded-full bg-gradient-to-r from-cyan-500 to-blue-600 text-white text-xs font-semibold">
                          继续播放
                        </button>
                      </div>
                    );
                  })
                )}
              </div>
            </div>
          )}
        </div>

        {/* Bottom Navigation Bar (R.id.bottom_nav, 60dp) - Exactly 5 tabs */}
        <div className="h-14 bg-[#0d111a] border-t border-white/5 grid grid-cols-5 items-center select-none">
          {[
            { key: 'home', label: '首页' },
            { key: 'category', label: '分类' },
            { key: 'top', label: '榜单' },
            { key: 'daily', label: '更新' },
            { key: 'mine', label: '收藏' }
          ].map(t => {
            const isCur = navTab === t.key;
            return (
              <button
                key={t.key}
                onClick={() => setNavTab(t.key as any)}
                className="h-full flex flex-col items-center justify-center transition-all"
              >
                <span
                  className={`text-xs transition-colors ${
                    isCur ? 'text-cyan-400 font-bold text-sm' : 'text-slate-400 font-medium'
                  }`}
                >
                  {t.label}
                </span>
                {isCur && <span className="w-4 h-0.5 rounded-full bg-cyan-400 mt-0.5" />}
              </button>
            );
          })}
        </div>
      </div>
    );
  }

  // Phone DetailActivity (activity_detail.xml)
  function renderPhoneDetailScreen(work: MediaWork) {
    if (isDetailLoading) {
      return (
        <div className="flex-1 flex items-center justify-center bg-[#07090e] p-6">
          <LoadingHUD
            message="正在解析影视详情与全网播放线路…"
            subMessage="聚合 4K 蓝光流媒体 · 防盗链自动注入 · 智能跳过片头"
          />
        </div>
      );
    }

    return (
      <div className="flex-1 flex flex-col bg-[#07090e] overflow-y-auto text-xs p-3 space-y-4">
        <div className="flex items-center justify-between select-none">
          <button
            onClick={() => setCurrentActivity('main')}
            className="px-3 py-1.5 rounded-full bg-[#121620] border border-white/10 text-white font-semibold text-xs hover:border-cyan-500/40"
          >
            〈 返回选片
          </button>
          <button
            onClick={() => toggleFavorite(work.id)}
            className={`px-3 py-1.5 rounded-full border text-xs font-semibold transition-all ${
              isFav(work.id)
                ? 'bg-gradient-to-r from-cyan-500 to-blue-600 text-white border-transparent'
                : 'bg-[#121620] text-slate-300 border-white/10'
            }`}
          >
            {isFav(work.id) ? '★ 已收藏' : '☆ 收藏'}
          </button>
        </div>

        <div className="p-3 rounded-xl bg-[#0d111a] border border-white/10 flex gap-3 shadow-md">
          <div className="w-[110px] h-[150px] rounded-lg overflow-hidden flex-shrink-0 bg-[#121620]">
            <PosterImage src={work.poster} alt={work.title} className="w-full h-full object-cover" />
          </div>
          <div className="flex-1 flex flex-col justify-between py-1 min-w-0">
            <div className="space-y-1">
              <h2 className="font-bold text-base text-white truncate">{work.title}</h2>
              <div className="text-[11px] text-cyan-400 font-semibold">
                {work.category} · {work.year} · {work.area}
              </div>
              <div className="text-[11px] text-slate-400">
                {work.mainType === 'movie' ? '正片 HD4K' : `共 ${work.totalEps || 12} 集 · 连载热播`}
              </div>
              <div className="text-[11px] text-cyan-300 font-bold">★ 评分 {work.score}</div>
            </div>
            <button
              onClick={() => startPlayer(work, 1)}
              className="self-start px-4 py-2 rounded-full bg-gradient-to-r from-cyan-500 to-blue-600 text-white font-bold text-xs shadow-md shadow-cyan-500/20 flex items-center gap-1 active:scale-95"
            >
              <Play className="w-3.5 h-3.5 fill-white" />
              <span> ▶ 立即播放 </span>
            </button>
          </div>
        </div>

        <div className="space-y-1.5">
          <div className="font-bold text-sm text-white">剧情简介</div>
          <div className="p-3 rounded-xl bg-[#0d111a] border border-white/5 text-[11px] text-slate-300 leading-relaxed">
            {work.desc}
          </div>
        </div>

        <div className="space-y-1.5">
          <div className="font-bold text-sm text-white">播放线路</div>
          <div className="flex items-center gap-2 overflow-x-auto pb-1 scrollbar-none">
            {[
              { id: 1, name: '线路1 (蓝光4K专线)' },
              { id: 2, name: '线路2 (极速CDN专线)' },
              { id: 3, name: '线路3 (官方原画备用)' }
            ].map(line => (
              <button
                key={line.id}
                onClick={() => setActiveLineId(line.id)}
                className={`px-3 py-1.5 rounded-full text-xs font-semibold whitespace-nowrap transition-all ${
                  activeLineId === line.id
                    ? 'bg-gradient-to-r from-cyan-500 to-blue-600 text-white'
                    : 'bg-[#121620] text-slate-400 border border-white/5 hover:text-white'
                }`}
              >
                {line.name}
              </button>
            ))}
          </div>
        </div>

        <div className="space-y-1.5 pb-6">
          <div className="font-bold text-sm text-white flex items-center justify-between">
            <span>剧集选集</span>
            <span className="text-[11px] text-slate-400 font-normal">
              {work.mainType === 'movie' ? '正片播放' : `共 ${work.totalEps || 12} 集`}
            </span>
          </div>
          <div className="grid grid-cols-4 gap-2">
            {Array.from({ length: work.mainType === 'movie' ? 1 : work.totalEps || 12 }, (_, i) => i + 1).map(ep => (
              <button
                key={ep}
                onClick={() => startPlayer(work, ep)}
                className="py-2.5 rounded-xl bg-[#0d111a] border border-white/5 hover:border-cyan-500/40 text-center font-bold text-xs text-white transition-all active:scale-95"
              >
                {work.mainType === 'movie' ? '正片' : `第${ep < 10 ? '0' + ep : ep}集`}
              </button>
            ))}
          </div>
        </div>
      </div>
    );
  }

  // Phone PlayerActivity (activity_player.xml)
  function renderPhonePlayerScreen(work: MediaWork) {
    return (
      <div className="flex-1 flex flex-col bg-black relative overflow-hidden select-none">
        <div className="absolute inset-0 flex items-center justify-center">
          <img
            src={work.banner || work.poster}
            alt=""
            className={`w-full h-full object-cover transition-opacity duration-700 ${
              isPlaying ? 'opacity-40 scale-105' : 'opacity-65'
            }`}
          />
          <div className="absolute inset-0 bg-gradient-to-t from-black via-black/30 to-black/70" />
        </div>

        {isPlayerLoading && (
          <div className="absolute inset-0 z-30 flex items-center justify-center bg-black/85 backdrop-blur-md">
            <LoadingHUD
              message={`正在解析第 ${activeEpisode} 集 4K 播放线路…`}
              subMessage="直连全球高速 CDN · 防盗链自动注入 · 智能跳过片头"
            />
          </div>
        )}

        <div
          onClick={() => setPlayerOverlayVisible(prev => !prev)}
          className={`absolute inset-0 flex flex-col justify-between p-3 z-20 transition-opacity duration-300 ${
            playerOverlayVisible ? 'opacity-100' : 'opacity-0 pointer-events-none'
          }`}
        >
          {/* Top Bar - Optimized for Portrait vs Landscape */}
          <div
            onClick={e => e.stopPropagation()}
            className={`w-full bg-gradient-to-b from-black/90 via-black/50 to-transparent -mx-3 -mt-3 p-3 text-white select-none transition-all ${
              phoneOrientation === 'portrait' ? 'space-y-2' : ''
            }`}
          >
            {phoneOrientation === 'portrait' ? (
              // 📱 Portrait Mode: 2-tier spacious layout, completely eliminating button crowding
              <>
                <div className="flex items-center justify-between">
                  <button
                    onClick={() => setCurrentActivity('detail')}
                    className="h-7 px-2.5 rounded-full bg-[#121620]/90 border border-white/15 text-white font-semibold text-xs hover:border-cyan-500/40 flex items-center gap-1 active:scale-95 transition-all shadow-sm"
                  >
                    <ArrowLeft className="w-3.5 h-3.5 text-cyan-400" />
                    <span>〈 返回</span>
                  </button>

                  <div className="flex items-center gap-2">
                    <button
                      onClick={() => setPhoneOrientation('landscape')}
                      className="h-7 px-2.5 rounded-full bg-gradient-to-r from-cyan-500/25 to-blue-600/25 border border-cyan-400/40 text-cyan-300 text-xs font-bold flex items-center gap-1 hover:bg-cyan-500/35 active:scale-95 transition-all shadow-md shadow-cyan-500/10"
                      title="点击旋转为全屏横屏模式"
                    >
                      <Rotate3d className="w-3.5 h-3.5 text-cyan-400" />
                      <span>🔄 旋转横屏</span>
                    </button>

                    <button
                      onClick={() => setIsCinemaMaximized(true)}
                      className="h-7 w-7 rounded-full bg-[#121620]/90 hover:bg-cyan-500/20 text-slate-200 hover:text-cyan-300 border border-white/15 hover:border-cyan-400/50 flex items-center justify-center active:scale-95 transition-all shadow-sm"
                      title="开启影院最大化模式"
                    >
                      <Maximize2 className="w-3.5 h-3.5" />
                    </button>
                  </div>
                </div>

                <div className="flex items-center gap-2 px-0.5">
                  <span className="font-extrabold text-sm text-white truncate max-w-[210px] drop-shadow-md">
                    {work.title}
                  </span>
                  <span className="px-2 py-0.5 rounded-full bg-cyan-500/20 text-cyan-300 border border-cyan-500/30 text-[10px] font-mono font-bold flex-shrink-0">
                    第{activeEpisode < 10 ? '0' + activeEpisode : activeEpisode}集
                  </span>
                </div>
              </>
            ) : (
              // 🔄 Landscape Mode: Single spacious wide row
              <div className="flex items-center justify-between text-xs">
                <div className="flex items-center gap-3 min-w-0">
                  <button
                    onClick={() => setCurrentActivity('detail')}
                    className="h-8 px-3 rounded-full bg-[#121620]/90 border border-white/15 text-white font-semibold hover:border-cyan-500/40 flex items-center gap-1.5 active:scale-95 transition-all flex-shrink-0"
                  >
                    <ArrowLeft className="w-4 h-4 text-cyan-400" />
                    <span>〈 返回</span>
                  </button>
                  <span className="font-extrabold text-sm text-white truncate max-w-sm drop-shadow-md">
                    {work.title}
                  </span>
                  <span className="px-2.5 py-0.5 rounded-full bg-cyan-500/20 text-cyan-300 border border-cyan-500/30 text-xs font-mono font-bold flex-shrink-0">
                    第{activeEpisode < 10 ? '0' + activeEpisode : activeEpisode}集
                  </span>
                </div>

                <div className="flex items-center gap-2.5 flex-shrink-0">
                  <button
                    onClick={() => setPhoneOrientation('portrait')}
                    className="h-8 px-3 rounded-full bg-cyan-500/20 text-cyan-300 border border-cyan-500/40 text-xs font-semibold hover:bg-cyan-500/30 flex items-center gap-1.5 active:scale-95 transition-all"
                  >
                    <Rotate3d className="w-3.5 h-3.5 text-cyan-400" />
                    <span>📱 恢复竖屏</span>
                  </button>
                  <button
                    onClick={() => setIsCinemaMaximized(true)}
                    className="h-8 w-8 rounded-full bg-[#121620]/90 hover:bg-cyan-500/20 text-slate-200 hover:text-cyan-300 border border-white/15 hover:border-cyan-400/50 flex items-center justify-center active:scale-95 transition-all"
                    title="影院最大化播放"
                  >
                    <Maximize2 className="w-4 h-4" />
                  </button>
                </div>
              </div>
            )}
          </div>

          {/* Center Cluster */}
          <div onClick={e => e.stopPropagation()} className="flex items-center justify-center gap-6">
            <button
              onClick={() => setPlayTime(Math.max(0, playTime - 10))}
              className="w-10 h-10 rounded-full bg-[#121620]/80 text-white border border-white/10 flex items-center justify-center font-bold text-xs active:scale-90"
              title="快退10秒"
            >
              ⏪10
            </button>
            <button
              onClick={() => setIsPlaying(!isPlaying)}
              className="w-14 h-14 rounded-full bg-gradient-to-r from-cyan-400 to-blue-600 text-white flex items-center justify-center shadow-xl shadow-cyan-500/40 active:scale-95"
            >
              {isPlaying ? <Pause className="w-6 h-6 fill-white" /> : <Play className="w-6 h-6 ml-0.5 fill-white" />}
            </button>
            <button
              onClick={() => setPlayTime(Math.min(duration, playTime + 10))}
              className="w-10 h-10 rounded-full bg-[#121620]/80 text-white border border-white/10 flex items-center justify-center font-bold text-xs active:scale-90"
              title="快进10秒"
            >
              10⏩
            </button>
          </div>

          {/* Bottom Bar */}
          <div
            onClick={e => e.stopPropagation()}
            className="bg-[#07090e]/90 backdrop-blur-md rounded-xl p-2.5 border border-white/10 space-y-2"
          >
            <div className="relative w-full h-2 bg-white/20 rounded-full overflow-hidden cursor-pointer">
              <div
                className="h-full bg-gradient-to-r from-cyan-400 to-blue-500 rounded-full transition-all"
                style={{ width: `${(playTime / duration) * 100}%` }}
              />
            </div>
            <div className="flex items-center justify-between text-[11px] text-white">
              <span className="font-mono text-slate-300">
                {Math.floor(playTime / 60)}:{String(playTime % 60).padStart(2, '0')} /{' '}
                {Math.floor(duration / 60)}:{String(duration % 60).padStart(2, '0')}
              </span>
              <div className="flex items-center gap-2">
                <button
                  onClick={() =>
                    setPlaybackSpeed(
                      playbackSpeed === '1.0x'
                        ? '1.25x'
                        : playbackSpeed === '1.25x'
                        ? '1.5x'
                        : playbackSpeed === '1.5x'
                        ? '2.0x'
                        : playbackSpeed === '2.0x'
                        ? '0.75x'
                        : '1.0x'
                    )
                  }
                  className="px-2 py-0.5 rounded bg-[#121620] text-cyan-300 border border-cyan-500/30 font-mono text-[10px]"
                >
                  {playbackSpeed}
                </button>
                <button
                  onClick={() => {
                    const next = activeEpisode + 1;
                    if (next <= (work.totalEps || 12)) {
                      setActiveEpisode(next);
                      setPlayTime(0);
                    }
                  }}
                  className="px-2 py-0.5 rounded bg-[#121620] text-white border border-white/10 text-[10px]"
                >
                  下一集 ⏭
                </button>
                <button
                  onClick={() =>
                    setPhoneOrientation(prev => (prev === 'portrait' ? 'landscape' : 'portrait'))
                  }
                  className="px-2 py-0.5 rounded bg-cyan-500/20 text-cyan-300 border border-cyan-500/30 text-[10px] font-semibold"
                >
                  {phoneOrientation === 'landscape' ? '📱 竖屏' : '🔄 横屏'}
                </button>
              </div>
            </div>
          </div>
        </div>
      </div>
    );
  }

  // Phone SearchActivity (activity_search.xml)
  function renderPhoneSearchScreen() {
    const searchResults = CATALOG_DATABASE.filter(
      w => !searchKeyword || w.title.includes(searchKeyword) || w.category.includes(searchKeyword)
    );

    return (
      <div className="flex-1 flex flex-col bg-[#07090e] overflow-y-auto text-xs p-3 space-y-4">
        <div className="flex items-center gap-2">
          <button
            onClick={() => setCurrentActivity('main')}
            className="p-1.5 rounded-full bg-[#121620] text-slate-300 hover:text-white border border-white/10"
          >
            <ArrowLeft className="w-4 h-4" />
          </button>
          <input
            type="text"
            value={searchKeyword}
            onChange={e => setSearchKeyword(e.target.value)}
            placeholder="搜索电影、电视剧、动漫…"
            className="flex-1 bg-[#121620] border border-white/10 rounded-full px-3.5 py-1.5 text-xs text-white outline-none focus:border-cyan-500"
          />
          <button
            onClick={() => {
              if (searchKeyword && !searchHistory.includes(searchKeyword)) {
                setSearchHistory([searchKeyword, ...searchHistory.slice(0, 7)]);
              }
            }}
            className="px-3.5 py-1.5 rounded-full bg-gradient-to-r from-cyan-500 to-blue-600 text-white font-bold text-xs"
          >
            搜索
          </button>
        </div>

        <div className="space-y-1.5">
          <div className="font-bold text-white flex items-center gap-1.5 text-xs">
            <Flame className="w-3.5 h-3.5 text-cyan-400" />
            <span>全网热搜</span>
          </div>
          <div className="flex flex-wrap gap-1.5">
            {['繁花', '漫长的季节', '三体', '庆余年', '狂飙', '奥本海默', '沙丘2'].map(kw => (
              <button
                key={kw}
                onClick={() => setSearchKeyword(kw)}
                className="px-2.5 py-1 rounded-full bg-[#121620] border border-white/5 text-[11px] text-slate-300 hover:text-cyan-300"
              >
                {kw}
              </button>
            ))}
          </div>
        </div>

        <div className="space-y-2 pt-2 border-t border-white/5">
          <div className="text-[11px] font-bold text-cyan-300">搜索结果 ({searchResults.length}部)：</div>
          <div className="space-y-2">
            {searchResults.map(item => (
              <div
                key={item.id}
                onClick={() => openDetail(item)}
                className="p-2.5 rounded-xl bg-[#0d111a] border border-white/5 hover:border-cyan-500/40 transition-all flex items-center gap-3 cursor-pointer"
              >
                <div className="w-[65px] h-[90px] rounded-lg overflow-hidden flex-shrink-0 bg-[#121620]">
                  <PosterImage src={item.poster} alt={item.title} className="w-full h-full object-cover" />
                </div>
                <div className="flex-1 min-w-0 space-y-1">
                  <h4 className="font-bold text-sm text-white truncate">{item.title}</h4>
                  <div className="text-[11px] text-slate-400">{item.category} · {item.year}</div>
                  <div className="text-[11px] text-cyan-400 font-medium">{item.progress}</div>
                  <div className="text-[11px] text-cyan-300 font-semibold">★ {item.score}</div>
                </div>
              </div>
            ))}
          </div>
        </div>
      </div>
    );
  }

  // --------------------------------------------------------------------------
  // TV ACTIVITY SCREEN RENDERER (16:9 WIDESCREEN LEANBACK TV LAYOUT)
  // --------------------------------------------------------------------------
  function renderTvActivityScreen() {
    return (
      <div id="hm-tv-screen" className="flex-1 flex min-h-0 flex-col">
        {currentActivity === 'player' && activeWork
          ? renderTvPlayerScreen(activeWork)
          : currentActivity === 'detail' && activeWork
            ? renderTvDetailScreen(activeWork)
            : renderTvMainScreen()}
      </div>
    );
  }

  // TV MainActivity (16:9 Widescreen TV Interface)
  function renderTvMainScreen() {
    const visibleList =
      navTab === 'category'
        ? CATALOG_DATABASE.filter(w => w.mainType === mainCatType)
        : CATALOG_DATABASE;

    return (
      <div className="flex-1 flex flex-col bg-[#07090e] overflow-hidden text-xs">
        {/* TV Top Leanback Bar */}
        <div className="h-12 px-6 bg-[#0d111a] border-b border-white/5 flex items-center justify-between select-none">
          <div className="flex items-center gap-4">
            <div className="flex items-center gap-2">
              <span className="font-extrabold text-base text-white">HM 影视 · TV 版</span>
              <span className="text-[10px] px-2 py-0.2 rounded bg-cyan-500/20 text-cyan-300 border border-cyan-500/30 font-bold">
                智能电视 4K
              </span>
            </div>

            {/* 5 Leanback TV Tabs */}
            <div className="flex items-center gap-1 ml-4">
              {[
                { key: 'home', label: '首页' },
                { key: 'category', label: '分类' },
                { key: 'top', label: '榜单' },
                { key: 'daily', label: '更新' },
                { key: 'mine', label: '收藏' }
              ].map(t => {
                const isCur = navTab === t.key;
                return (
                  <button
                    key={t.key}
                    onClick={() => {
                      setNavTab(t.key as any);
                      setTvFocusIndex(0);
                    }}
                    className={`tv-focusable px-3 py-1 rounded-full text-xs font-bold transition-all ${
                      isCur
                        ? 'bg-gradient-to-r from-cyan-500 to-blue-600 text-white shadow-md shadow-cyan-500/20'
                        : 'text-slate-400 hover:text-white'
                    }`}
                  >
                    {t.label}
                  </button>
                );
              })}
            </div>
          </div>

          <div className="flex items-center gap-2">
            <span className="text-[11px] text-cyan-400 font-mono">方向键移动 · OK 选择 · 返回键上一级</span>
          </div>
        </div>

        {/* TV Main Widescreen Content Body */}
        <div className="flex-1 overflow-y-auto p-4 space-y-4">
          {navTab === 'home' && (
            <div className="space-y-4">
              {/* TV Featured Widescreen Banner */}
              <div
                onClick={() => openDetail(heroWork)}
                className="tv-focusable p-3.5 rounded-2xl bg-gradient-to-r from-[#0e162a] to-[#0a0f1d] border border-white/10 transition-all flex items-center gap-5 cursor-pointer"
              >
                <div className="w-[128px] h-[192px] rounded-xl overflow-hidden flex-shrink-0 bg-[#121620]">
                  <PosterImage src={heroWork.poster} alt={heroWork.title} className="w-full h-full object-cover" />
                </div>
                <div className="flex-1 space-y-2 py-1">
                  <span className="inline-block px-2.5 py-0.5 rounded-full bg-gradient-to-r from-cyan-400 to-blue-600 text-white font-bold text-xs">
                    电视精选首推 · 遥控器按 OK 播放
                  </span>
                  <h2 className="text-xl font-extrabold text-white">{heroWork.title}</h2>
                  <div className="text-xs text-cyan-300 font-semibold">
                    {heroWork.category} · {heroWork.year} · ★ {heroWork.score} · {heroWork.progress}
                  </div>
                  <p className="text-xs text-slate-300 line-clamp-2 max-w-2xl leading-relaxed">
                    {heroWork.desc}
                  </p>
                  <div className="pt-2 flex items-center gap-3">
                    <button
                      onClick={e => {
                        e.stopPropagation();
                        startPlayer(heroWork, 1);
                      }}
                      className="px-5 py-2 rounded-xl bg-gradient-to-r from-cyan-500 to-blue-600 text-white font-bold text-xs shadow-lg shadow-cyan-500/30 flex items-center gap-2"
                    >
                      <Play className="w-4 h-4 fill-white" />
                      <span>立即在电视全屏起播</span>
                    </button>
                  </div>
                </div>
              </div>

              {/* TV Grid Section Title */}
              <div className="text-sm font-bold text-white flex items-center justify-between pt-1">
                <span>🔥 热门大屏影视专区（按遥控器方向键上下左右选择）</span>
                <span className="text-xs text-slate-400">已对齐 TV D-Pad Focus 状态</span>
              </div>

              {/* 4-Column TV Poster Grid */}
              <div className="grid grid-cols-6 gap-3.5 pb-4">
                {visibleList.map((item, idx) => {
                  return (
                    <div
                      key={item.id}
                      className="tv-focusable p-2.5 rounded-xl bg-[#0d111a] border border-white/5 transition-all cursor-pointer flex flex-col gap-2"
                      onClick={() => {
                        setTvFocusIndex(idx);
                        openDetail(item);
                      }}
                    >
                      <div className="aspect-[2/3] w-full rounded-lg overflow-hidden bg-[#121620] relative">
                        <PosterImage src={item.poster} alt={item.title} className="w-full h-full object-cover" />
                        <span className="absolute top-1.5 right-1.5 px-1.5 py-0.5 rounded bg-black/80 text-cyan-300 font-bold text-[10px]">
                          ★ {item.score}
                        </span>
                      </div>
                      <div className="min-w-0 space-y-0.5">
                        <h4 className="font-bold text-xs text-white truncate">{item.title}</h4>
                        <div className="text-[11px] text-slate-400 truncate">
                          {item.category} · {item.progress}
                        </div>
                      </div>
                    </div>
                  );
                })}
              </div>
            </div>
          )}

          {navTab === 'category' && (
            <div className="space-y-4">
              {/* TV Primary Channel Pills */}
              <div className="flex items-center gap-2 overflow-x-auto pb-1 scrollbar-none">
                {CHANNEL_TABS.map(cat => (
                  <button
                    key={cat.key}
                    data-tv-focusable="true"
                    onClick={() => {
                      setMainCatType(cat.key as any);
                      setFilterGenre('all');
                      setTvFocusIndex(0);
                    }}
                    className={`px-4 py-1.5 rounded-full text-xs font-bold transition-all whitespace-nowrap ${
                      mainCatType === cat.key
                        ? 'bg-gradient-to-r from-cyan-500 to-blue-600 text-white shadow-md shadow-cyan-500/25'
                        : 'bg-[#121620] text-slate-400 hover:text-white border border-white/5'
                    }`}
                  >
                    {cat.label}
                  </button>
                ))}
              </div>

              {/* TV Sub-genre Pills */}
              <div className="flex items-center gap-2 overflow-x-auto pb-1 scrollbar-none">
                {(GENRES_BY_CHANNEL[mainCatType] || [{ label: '全部类型', value: 'all' }]).map(sub => (
                  <button
                    key={sub.value}
                    data-tv-focusable="true"
                    onClick={() => {
                      setFilterGenre(sub.value);
                      setTvFocusIndex(0);
                    }}
                    className={`px-3 py-1 rounded-full text-[11px] font-semibold whitespace-nowrap transition-all ${
                      filterGenre === sub.value
                        ? 'bg-cyan-500/30 text-cyan-300 border border-cyan-400/50'
                        : 'bg-[#121620] text-slate-400 hover:text-white border border-white/5'
                    }`}
                  >
                    {sub.label}
                  </button>
                ))}
              </div>

              <div className="grid grid-cols-6 gap-3.5 pb-4">
                {currentCategoryWorks.map((item, idx) => {
                  return (
                    <div
                      key={item.id}
                      data-tv-focusable="true"
                      tabIndex={0}
                      onClick={() => {
                        setTvFocusIndex(idx);
                        openDetail(item);
                      }}
                      className="tv-focusable p-2.5 rounded-xl bg-[#0d111a] border border-white/5 transition-all cursor-pointer flex flex-col gap-2"
                    >
                      <div className="aspect-[2/3] w-full rounded-lg overflow-hidden bg-[#121620] relative">
                        <PosterImage src={item.poster} alt={item.title} className="w-full h-full object-cover" />
                        <span className="absolute top-1.5 right-1.5 px-1.5 py-0.5 rounded bg-black/80 text-cyan-300 font-bold text-[10px]">
                          ★ {item.score}
                        </span>
                      </div>
                      <div className="min-w-0 space-y-0.5">
                        <h4 className="font-bold text-xs text-white truncate">{item.title}</h4>
                        <div className="text-[11px] text-slate-400 truncate">
                          {item.category} · {item.progress}
                        </div>
                      </div>
                    </div>
                  );
                })}
              </div>
            </div>
          )}

          {navTab === 'top' && (
            <div className="space-y-4">
              <div className="text-sm font-bold text-white">全网大屏播放风云榜</div>
              <div className="grid grid-cols-2 gap-3 pb-4">
                {CATALOG_DATABASE.slice(0, 6).map((item, idx) => (
                  <div
                    key={item.id}
                    onClick={() => openDetail(item)}
                    className="p-3 rounded-xl bg-[#0d111a] border border-white/5 flex items-center gap-3 cursor-pointer hover:border-cyan-500/40"
                  >
                    <div
                      className={`w-7 h-7 rounded-lg flex items-center justify-center font-bold text-sm ${
                        idx < 3
                          ? 'bg-gradient-to-br from-cyan-400 to-blue-600 text-white'
                          : 'bg-slate-800 text-slate-400'
                      }`}
                    >
                      {idx + 1}
                    </div>
                    <div className="w-[60px] h-[85px] rounded-lg overflow-hidden bg-[#121620] flex-shrink-0">
                      <PosterImage src={item.poster} alt={item.title} className="w-full h-full object-cover" />
                    </div>
                    <div className="min-w-0 space-y-1 flex-1">
                      <h4 className="font-bold text-sm text-white truncate">{item.title}</h4>
                      <div className="text-xs text-slate-400">{item.category} · {item.progress}</div>
                      <div className="text-xs text-cyan-300 font-bold">★ 评分 {item.score}</div>
                    </div>
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>
      </div>
    );
  }

  // TV Detail Screen
  function renderTvDetailScreen(work: MediaWork) {
    return (
      <div className="flex-1 flex flex-col bg-[#07090e] p-6 overflow-y-auto text-xs space-y-5">
        <div className="flex items-center justify-between">
          <button
            onClick={() => setCurrentActivity('main')}
            className="px-4 py-2 rounded-xl bg-slate-800 text-white font-bold text-xs flex items-center gap-2 hover:bg-slate-700 border border-white/10"
          >
            <ArrowLeft className="w-4 h-4" />
            <span>遥控器按返回 或 点击返回电视首页</span>
          </button>
          <span className="text-cyan-300 font-bold text-xs">{work.category} · 4K 蓝光流媒体专线</span>
        </div>

        <div className="flex gap-6 p-4 rounded-2xl bg-[#0d111a] border border-cyan-500/30">
          <div className="w-[140px] h-[200px] rounded-xl overflow-hidden bg-[#121620] flex-shrink-0">
            <PosterImage src={work.poster} alt={work.title} className="w-full h-full object-cover" />
          </div>
          <div className="flex-1 space-y-2 py-1">
            <h1 className="text-2xl font-black text-white">{work.title}</h1>
            <div className="text-xs text-cyan-300 font-semibold">
              {work.year} · {work.area} · ★ {work.score} · {work.progress}
            </div>
            <p className="text-xs text-slate-300 leading-relaxed max-w-2xl">{work.desc}</p>
            <div className="pt-3 flex items-center gap-3">
              <button
                onClick={() => startPlayer(work, 1)}
                className="px-6 py-2.5 rounded-xl bg-gradient-to-r from-cyan-500 to-blue-600 text-white font-bold text-sm shadow-xl shadow-cyan-500/30 flex items-center gap-2 hover:scale-105 active:scale-95 transition-all"
              >
                <Play className="w-4 h-4 fill-white" />
                <span>立即全屏大屏播放 (OK)</span>
              </button>
            </div>
          </div>
        </div>

        {/* TV Episode Selector */}
        <div className="space-y-2">
          <div className="text-sm font-bold text-white">剧集快速选集（遥控器可直接导航）</div>
          <div className="grid grid-cols-6 gap-2.5">
            {Array.from({ length: work.mainType === 'movie' ? 1 : work.totalEps || 12 }, (_, i) => i + 1).map(ep => (
              <button
                key={ep}
                onClick={() => startPlayer(work, ep)}
                className="py-3 rounded-xl bg-[#0d111a] border border-white/10 hover:border-cyan-400 text-center font-bold text-xs text-white"
              >
                {work.mainType === 'movie' ? '正片' : `第${ep < 10 ? '0' + ep : ep}集`}
              </button>
            ))}
          </div>
        </div>
      </div>
    );
  }

  // TV Player Screen
  function renderTvPlayerScreen(work: MediaWork) {
    return (
      <div className="flex-1 flex flex-col bg-black relative overflow-hidden select-none">
        <div className="absolute inset-0 flex items-center justify-center">
          <img
            src={work.banner || work.poster}
            alt=""
            className={`w-full h-full object-cover transition-opacity duration-700 ${
              isPlaying ? 'opacity-40 scale-105' : 'opacity-65'
            }`}
          />
          <div className="absolute inset-0 bg-gradient-to-t from-black via-black/30 to-black/70" />
        </div>

        {isPlayerLoading && (
          <div className="absolute inset-0 z-30 flex items-center justify-center bg-black/85 backdrop-blur-md">
            <LoadingHUD
              message={`正在连接【电视蓝光4K专线】第 ${activeEpisode} 集…`}
              subMessage="直连全球超清 CDN · 防盗链自动注入 · 遥控器按 OK 键随时启停"
            />
          </div>
        )}

        <div className={`absolute inset-0 flex flex-col justify-between p-6 z-20 transition-opacity ${playerOverlayVisible ? 'opacity-100' : 'opacity-0 pointer-events-none'}`}>
          <div className="flex items-center justify-between text-white text-xs">
            <div className="flex items-center gap-3">
              <button
                onClick={() => setCurrentActivity('detail')}
                className="px-3 py-1.5 rounded-xl bg-[#121620]/90 border border-white/10 font-bold"
              >
                〈 返回电视详情 (BACK)
              </button>
              <span className="text-base font-extrabold">{work.title}</span>
              <span className="px-2 py-0.5 rounded-full bg-cyan-500/20 text-cyan-300 font-bold text-xs">
                第{activeEpisode < 10 ? '0' + activeEpisode : activeEpisode}集
              </span>
            </div>
            <div className="text-xs text-cyan-400 font-mono">按遥控器 ◀ ▶ 键快退/快进10秒</div>
          </div>

          <div className="flex items-center justify-center gap-8">
            <button
              onClick={() => setPlayTime(Math.max(0, playTime - 10))}
              className="w-12 h-12 rounded-full bg-black/60 text-white border border-white/10 flex items-center justify-center font-bold text-xs"
            >
              ⏪10s
            </button>
            <button
              onClick={() => setIsPlaying(!isPlaying)}
              data-tv-default-focus="true"
              className="w-16 h-16 rounded-full bg-gradient-to-r from-cyan-400 to-blue-600 text-white flex items-center justify-center shadow-2xl shadow-cyan-500/50"
            >
              {isPlaying ? <Pause className="w-8 h-8 fill-white" /> : <Play className="w-8 h-8 ml-1 fill-white" />}
            </button>
            <button
              onClick={() => setPlayTime(Math.min(duration, playTime + 10))}
              className="w-12 h-12 rounded-full bg-black/60 text-white border border-white/10 flex items-center justify-center font-bold text-xs"
            >
              10s⏩
            </button>
          </div>

          <div className="bg-[#07090e]/95 backdrop-blur-md rounded-2xl p-4 border border-white/10 space-y-2">
            <div className="relative w-full h-2.5 bg-white/20 rounded-full overflow-hidden">
              <div
                className="h-full bg-gradient-to-r from-cyan-400 to-blue-500 rounded-full"
                style={{ width: `${(playTime / duration) * 100}%` }}
              />
            </div>
            <div className="flex items-center justify-between text-xs text-white">
              <span className="font-mono text-cyan-300 font-bold">
                {Math.floor(playTime / 60)}:{String(playTime % 60).padStart(2, '0')} /{' '}
                {Math.floor(duration / 60)}:{String(duration % 60).padStart(2, '0')}
              </span>
              <span className="text-slate-400">大屏蓝光4K专线 · 帧率 60FPS</span>
            </div>
          </div>
        </div>
      </div>
    );
  }
}
