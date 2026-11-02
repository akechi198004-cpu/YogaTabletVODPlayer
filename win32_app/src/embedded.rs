//! HM 影视内置播放器后端。
//!
//! 选型：随包附带的 **mpv i686 Windows 运行时**（成熟播放器，不自行实现解码）。
//! 通过 `--wid` 把 mpv 嵌入应用自己的 Win32 子窗口，使用命名管道 JSON IPC
//! 控制播放、暂停、音量、进度和停止；mpv/FFmpeg 负责 HLS / MP4、硬件解码与
//! 网络缓冲。所有进程和 IPC 操作都在工作线程里，不阻塞 egui UI 线程。
//!
//! Windows 管道使用 `FILE_FLAG_OVERLAPPED` 打开，读写各自持有独立的
//! OVERLAPPED 事件，因此读取阻塞时仍可发送命令；取消和超时通过
//! `CancelIoEx` 完成。播放器生命周期由后台工作线程串行管理，UI 线程只投递
//! 命令，不等待进程退出或线程 join。
//!
//! 内置播放不依赖 VLC/libVLC。非 Windows 平台或发布包缺少 mpv 时，
//! `available()` 返回 false；界面会给出明确提示，并允许用户主动选择外部
//! VLC 兼容模式，但不会静默启动外部程序。
//!
//! VOD 需要可定位进度：工作线程通过 `observe_property` 订阅 time-pos、
//! duration、pause 与 volume，属性变化以带会话代次的事件回到 UI。

// Windows 实现之外的分支在 Linux 上只用于单元测试，避免整模块出现 dead_code 噪声。
#![cfg_attr(not(windows), allow(dead_code))]

use std::path::PathBuf;
use std::sync::mpsc::{self, Receiver, Sender};
use std::thread;
use std::time::Duration;

#[cfg(windows)]
use std::sync::Arc;
#[cfg(windows)]
use std::time::Instant;

#[derive(Debug, thiserror::Error)]
pub enum EmbeddedError {
    #[error("当前平台不支持内置播放器")]
    #[allow(dead_code)]
    Unsupported,
    #[error("未找到随包 mpv 运行时（mpv/mpv.exe）")]
    MissingRuntime,
    #[error("内置播放器初始化失败：{0}")]
    Init(String),
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct LoadOptions {
    pub url: String,
    pub user_agent: String,
    pub referer: String,
    pub disable_hw_decode: bool,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum EndReason {
    Eof,
    Stop,
    Error,
    Quit,
    Redirect,
}

#[derive(Clone, Debug, PartialEq)]
pub enum PlayerProperty {
    TimePos(f64),
    Duration(Option<f64>),
    Pause(bool),
    Volume(f64),
}

#[derive(Clone, Debug)]
pub enum EmbeddedEvent {
    Loading {
        load_id: u64,
    },
    Playing {
        load_id: u64,
    },
    Ended {
        load_id: u64,
        reason: EndReason,
        error: Option<String>,
    },
    ProcessExited {
        load_id: u64,
        code: Option<i32>,
    },
    Error {
        load_id: u64,
        message: String,
    },
    Property {
        load_id: u64,
        property: PlayerProperty,
    },
}

/// 随包 mpv 运行时的位置。Windows 发布包把它放在 exe 同目录的 `mpv/`。
pub fn find_mpv() -> Option<PathBuf> {
    #[cfg(windows)]
    {
        let mut roots = Vec::new();
        if let Some(exe_dir) = std::env::current_exe()
            .ok()
            .and_then(|path| path.parent().map(PathBuf::from))
        {
            roots.push(exe_dir);
        }
        if let Ok(current) = std::env::current_dir() {
            roots.push(current);
        }
        find_mpv_in_roots(&roots)
    }
    #[cfg(not(windows))]
    {
        None
    }
}

/// 在给定根目录下按约定位置查找 mpv。拆出来便于单元测试。
fn find_mpv_in_roots(roots: &[PathBuf]) -> Option<PathBuf> {
    for root in roots {
        for relative in ["mpv/mpv.exe", "mpv.exe", "vendor/mpv/mpv.exe"] {
            let candidate = root.join(relative);
            if candidate.is_file() {
                return Some(candidate);
            }
        }
    }
    None
}

/// 当前平台/发布包是否具备内置播放器运行时。
pub fn available() -> bool {
    find_mpv().is_some()
}

/// 内置播放器句柄。工作线程在首次加载时才启动 mpv 进程。
///
/// 该句柄被应用长期持有：换台、停止、全屏重启只发送命令，实际的进程
/// 清理和下一次启动都在工作线程里串行完成。UI 线程不会等待 `shutdown`、
/// `recv_timeout` 或线程 `join`。
pub struct EmbeddedPlayer {
    command_tx: Sender<PlayerCommand>,
    event_rx: Receiver<EmbeddedEvent>,
    worker: Option<thread::JoinHandle<()>>,
    #[cfg(windows)]
    job: Option<Arc<JobObject>>,
}

impl EmbeddedPlayer {
    pub fn new() -> Result<Self, EmbeddedError> {
        #[cfg(not(windows))]
        {
            Err(EmbeddedError::Unsupported)
        }

        #[cfg(windows)]
        {
            let mpv_path = find_mpv().ok_or(EmbeddedError::MissingRuntime)?;
            let job = JobObject::new().map(Arc::new);
            let (command_tx, command_rx) = mpsc::channel();
            let (event_tx, event_rx) = mpsc::channel();
            let worker_job = job.clone();
            let worker = thread::Builder::new()
                .name("embedded-mpv".to_owned())
                .spawn(move || worker_loop(mpv_path, command_rx, event_tx, worker_job))
                .map_err(|error| EmbeddedError::Init(error.to_string()))?;
            Ok(Self {
                command_tx,
                event_rx,
                worker: Some(worker),
                job,
            })
        }
    }

    /// 测试用：启动一个对每条命令都延迟 `delay` 的假工作线程，用于验证
    /// UI 线程上的命令投递不会等待后台清理。
    #[cfg(test)]
    pub(crate) fn new_test_slow(delay: Duration) -> Self {
        let (command_tx, command_rx) = mpsc::channel();
        let (_event_tx, event_rx) = mpsc::channel();
        let worker = thread::Builder::new()
            .name("embedded-test".to_owned())
            .spawn(move || test_worker_loop(command_rx, delay))
            .expect("spawn test worker");
        Self {
            command_tx,
            event_rx,
            worker: Some(worker),
            #[cfg(windows)]
            job: None,
        }
    }

    /// 把 mpv 视频输出绑定到应用创建的 Win32 子窗口。
    pub fn set_video_window(&self, hwnd: isize) {
        let _ = self.command_tx.send(PlayerCommand::SetHwnd(hwnd));
    }

    pub fn load(&self, load_id: u64, options: LoadOptions) {
        let _ = self
            .command_tx
            .send(PlayerCommand::Load { load_id, options });
    }

    pub fn pause(&self, paused: bool) {
        let _ = self.command_tx.send(PlayerCommand::Pause(paused));
    }

    pub fn set_volume(&self, volume: u8) {
        let _ = self.command_tx.send(PlayerCommand::Volume(volume.min(100)));
    }

    /// 绝对定位到指定秒数。负值/NaN 会被忽略。
    pub fn seek(&self, seconds: f64) {
        if seconds.is_finite() && seconds >= 0.0 {
            let _ = self.command_tx.send(PlayerCommand::Seek(seconds));
        }
    }

    /// 停止当前会话，但保留工作线程供下一次播放复用。清理在后台完成。
    pub fn stop(&self) {
        let _ = self.command_tx.send(PlayerCommand::Stop);
    }

    pub fn try_recv(&self) -> Option<EmbeddedEvent> {
        self.event_rx.try_recv().ok()
    }

    /// 非阻塞退出：只投递命令并分离工作线程。进程由工作线程/作业对象回收。
    pub fn shutdown_fast(&mut self) {
        let Some(worker) = self.worker.take() else {
            return;
        };
        let _ = self.command_tx.send(PlayerCommand::Shutdown { ack: None });
        // 应用退出/主动丢弃时直接终止作业对象，避免 UI 进程先退出后留下 mpv。
        #[cfg(windows)]
        if let Some(job) = &self.job {
            job.terminate();
        }
        drop(worker);
    }

    /// 仅在测试/受控退出时调用：有界等待后台清理完成；超时则终止作业对象中
    /// 的所有 mpv 进程。不会无限等待，也不会 join 工作线程。
    #[allow(dead_code)]
    pub fn shutdown_blocking(&mut self, timeout: Duration) {
        let Some(worker) = self.worker.take() else {
            return;
        };
        let (ack_tx, ack_rx) = mpsc::channel();
        let _ = self
            .command_tx
            .send(PlayerCommand::Shutdown { ack: Some(ack_tx) });
        if ack_rx.recv_timeout(timeout).is_err() {
            #[cfg(windows)]
            if let Some(job) = &self.job {
                job.terminate();
            }
        }
        drop(worker);
    }
}

impl Drop for EmbeddedPlayer {
    fn drop(&mut self) {
        self.shutdown_fast();
    }
}

/// 应用主窗口内承载 mpv 视频输出的子窗口。
pub struct VideoWindow {
    #[cfg(windows)]
    hwnd: isize,
    #[cfg(windows)]
    parent: isize,
    #[cfg(windows)]
    last_bounds: Option<(i32, i32, i32, i32)>,
    #[cfg(windows)]
    visible: bool,
    #[cfg(not(windows))]
    _private: (),
}

impl Default for VideoWindow {
    fn default() -> Self {
        Self::new()
    }
}

impl VideoWindow {
    pub fn new() -> Self {
        #[cfg(windows)]
        {
            Self {
                hwnd: 0,
                parent: 0,
                last_bounds: None,
                visible: false,
            }
        }
        #[cfg(not(windows))]
        {
            Self { _private: () }
        }
    }

    #[cfg(windows)]
    pub fn ensure(&mut self, parent: isize) -> Result<(), EmbeddedError> {
        use std::ptr::{null, null_mut};
        use windows_sys::Win32::Foundation::HWND;
        use windows_sys::Win32::System::LibraryLoader::GetModuleHandleW;
        use windows_sys::Win32::UI::WindowsAndMessaging::{
            CreateWindowExW, WS_CHILD, WS_CLIPCHILDREN, WS_CLIPSIBLINGS, WS_EX_NOACTIVATE,
        };

        const SS_BLACKRECT: u32 = 0x0000_0004;

        if self.hwnd != 0 && self.parent == parent {
            return Ok(());
        }
        self.destroy();
        if parent == 0 {
            return Err(EmbeddedError::Init("父窗口句柄为空".to_owned()));
        }

        let class: Vec<u16> = "STATIC\0".encode_utf16().collect();
        let title: Vec<u16> = "\0".encode_utf16().collect();
        let style = WS_CHILD | WS_CLIPCHILDREN | WS_CLIPSIBLINGS | SS_BLACKRECT;
        let hinstance = unsafe { GetModuleHandleW(null()) };
        let hwnd = unsafe {
            CreateWindowExW(
                WS_EX_NOACTIVATE,
                class.as_ptr(),
                title.as_ptr(),
                style,
                0,
                0,
                1,
                1,
                parent as HWND,
                null_mut(),
                hinstance,
                null(),
            )
        };
        if hwnd.is_null() {
            return Err(EmbeddedError::Init(format!(
                "创建视频子窗口失败：{}",
                std::io::Error::last_os_error()
            )));
        }
        self.hwnd = hwnd as isize;
        self.parent = parent;
        Ok(())
    }

    #[cfg(not(windows))]
    pub fn ensure(&mut self, _parent: isize) -> Result<(), EmbeddedError> {
        Ok(())
    }

    pub fn hwnd(&self) -> isize {
        #[cfg(windows)]
        {
            self.hwnd
        }
        #[cfg(not(windows))]
        {
            0
        }
    }

    #[cfg(windows)]
    pub fn set_bounds(&mut self, bounds: Option<(i32, i32, i32, i32)>) {
        use windows_sys::Win32::Foundation::HWND;
        use windows_sys::Win32::UI::WindowsAndMessaging::{
            SetWindowPos, ShowWindow, HWND_TOP, SWP_NOACTIVATE, SWP_SHOWWINDOW, SW_SHOW,
        };

        let Some((x, y, width, height)) = bounds else {
            self.hide();
            return;
        };
        if self.hwnd == 0 {
            return;
        }
        let width = width.max(1);
        let height = height.max(1);
        if self.visible && self.last_bounds == Some((x, y, width, height)) {
            return;
        }
        let hwnd = self.hwnd as HWND;
        unsafe {
            SetWindowPos(
                hwnd,
                HWND_TOP,
                x,
                y,
                width,
                height,
                SWP_NOACTIVATE | SWP_SHOWWINDOW,
            );
            ShowWindow(hwnd, SW_SHOW);
        }
        self.visible = true;
        self.last_bounds = Some((x, y, width, height));
    }

    #[cfg(not(windows))]
    pub fn set_bounds(&mut self, _bounds: Option<(i32, i32, i32, i32)>) {}

    #[cfg(windows)]
    pub fn hide(&mut self) {
        use windows_sys::Win32::Foundation::HWND;
        use windows_sys::Win32::UI::WindowsAndMessaging::{ShowWindow, SW_HIDE};

        if self.hwnd != 0 {
            unsafe {
                ShowWindow(self.hwnd as HWND, SW_HIDE);
            }
        }
        self.visible = false;
        self.last_bounds = None;
    }

    #[cfg(not(windows))]
    pub fn hide(&mut self) {}

    #[cfg(windows)]
    pub fn destroy(&mut self) {
        use windows_sys::Win32::Foundation::HWND;
        use windows_sys::Win32::UI::WindowsAndMessaging::DestroyWindow;

        if self.hwnd != 0 {
            unsafe {
                DestroyWindow(self.hwnd as HWND);
            }
        }
        self.hwnd = 0;
        self.parent = 0;
        self.last_bounds = None;
        self.visible = false;
    }

    #[cfg(not(windows))]
    pub fn destroy(&mut self) {}
}

impl Drop for VideoWindow {
    fn drop(&mut self) {
        self.destroy();
    }
}

// ---------------------------------------------------------------------- 协议

#[derive(Debug)]
enum PlayerCommand {
    SetHwnd(isize),
    Load { load_id: u64, options: LoadOptions },
    Pause(bool),
    Volume(u8),
    Seek(f64),
    Stop,
    Shutdown { ack: Option<Sender<()>> },
}

#[derive(Clone, Debug, PartialEq)]
enum MpvRawEvent {
    StartFile,
    FileLoaded,
    EndFile {
        reason: EndReason,
        error: Option<String>,
    },
    Property(PlayerProperty),
    Idle,
    PipeClosed,
}

/// 管道读取线程产生原始事件时即绑定会话代次，避免出队时误用新 load_id。
#[derive(Clone, Debug, PartialEq)]
struct RawEvent {
    load_id: u64,
    event: MpvRawEvent,
}

fn raw_event_is_current(current_load_id: u64, raw: &RawEvent) -> bool {
    raw.load_id != 0 && raw.load_id == current_load_id
}

fn parse_mpv_line(line: &str) -> Option<MpvRawEvent> {
    let value: serde_json::Value = serde_json::from_str(line.trim()).ok()?;
    let event = value.get("event")?.as_str()?;
    match event {
        "start-file" => Some(MpvRawEvent::StartFile),
        "file-loaded" => Some(MpvRawEvent::FileLoaded),
        "end-file" => {
            let reason = match value
                .get("reason")
                .and_then(|value| value.as_str())
                .unwrap_or("")
            {
                "eof" => EndReason::Eof,
                "stop" => EndReason::Stop,
                "quit" => EndReason::Quit,
                "error" => EndReason::Error,
                "redirect" => EndReason::Redirect,
                _ => EndReason::Eof,
            };
            let error = value
                .get("error")
                .and_then(|value| value.as_str())
                .map(str::to_owned);
            Some(MpvRawEvent::EndFile { reason, error })
        }
        "idle" => Some(MpvRawEvent::Idle),
        "property-change" => {
            let name = value.get("name").and_then(|value| value.as_str())?;
            let data = value.get("data")?;
            let property = match name {
                "time-pos" => PlayerProperty::TimePos(data.as_f64()?),
                "duration" => PlayerProperty::Duration(data.as_f64()),
                "pause" => PlayerProperty::Pause(data.as_bool()?),
                "volume" => PlayerProperty::Volume(data.as_f64()?),
                _ => return None,
            };
            Some(MpvRawEvent::Property(property))
        }
        _ => None,
    }
}

/// Windows 命名管道路径必须是 `\\.\pipe\<name>`。
fn pipe_name(process_id: u32, sequence: u64) -> String {
    format!(r"\\.\pipe\hmvod-{process_id}-{sequence}")
}

fn build_mpv_args(
    wid: isize,
    pipe: &str,
    options: &LoadOptions,
    volume: u8,
) -> Vec<std::ffi::OsString> {
    let mut args: Vec<std::ffi::OsString> = [
        "--no-config",
        "--no-terminal",
        "--idle=yes",
        "--force-window=yes",
        "--keep-open=no",
        "--osc=no",
        "--osd-level=0",
        "--input-default-bindings=no",
        "--input-vo-keyboard=no",
        "--load-scripts=no",
        "--no-ytdl",
        "--cache=yes",
        "--demuxer-max-bytes=32MiB",
        "--demuxer-max-back-bytes=8MiB",
        "--network-timeout=15",
        "--hr-seek=yes",
        "--background-color=#000000",
    ]
    .into_iter()
    .map(std::ffi::OsString::from)
    .collect();

    args.push(format!("--wid={wid}").into());
    args.push(format!("--input-ipc-server={pipe}").into());
    args.push(format!("--volume={}", volume.min(100)).into());
    args.push(if options.disable_hw_decode {
        "--hwdec=no".into()
    } else {
        "--hwdec=auto-safe".into()
    });
    if !options.user_agent.trim().is_empty() {
        args.push(format!("--user-agent={}", options.user_agent.trim()).into());
    }
    if !options.referer.trim().is_empty() {
        args.push(format!("--referrer={}", options.referer.trim()).into());
    }
    args
}

// ---------------------------------------------------------------------- Windows 异步管道

#[cfg(windows)]
#[derive(Debug, thiserror::Error)]
enum PipeError {
    #[error("IPC 操作失败：{0}")]
    Io(#[from] std::io::Error),
    #[error("IPC 操作超时")]
    Timeout,
    #[error("IPC 读取已取消")]
    Cancelled,
}

/// 真正双向并发的 overlapped 命名管道。
///
/// 读、写分别使用独立的 OVERLAPPED 事件；写操作用互斥锁保证 JSON 行不会
/// 交错。`cancel()` 会设置取消事件并调用 `CancelIoEx`，使阻塞中的读/写
/// 立即返回；句柄在最后一个 `Arc` 释放时关闭。
#[cfg(windows)]
struct AsyncPipe {
    handle: windows_sys::Win32::Foundation::HANDLE,
    cancel_event: windows_sys::Win32::Foundation::HANDLE,
    write_lock: std::sync::Mutex<()>,
    read_lock: std::sync::Mutex<()>,
    closed: std::sync::atomic::AtomicBool,
}

#[cfg(windows)]
unsafe impl Send for AsyncPipe {}
#[cfg(windows)]
unsafe impl Sync for AsyncPipe {}

#[cfg(windows)]
impl AsyncPipe {
    fn open(name: &str, timeout: Duration) -> Result<Self, PipeError> {
        use std::ptr::{null, null_mut};
        use windows_sys::Win32::Foundation::{
            CloseHandle, ERROR_FILE_NOT_FOUND, ERROR_PIPE_BUSY, GENERIC_READ, GENERIC_WRITE,
            INVALID_HANDLE_VALUE,
        };
        use windows_sys::Win32::Storage::FileSystem::{
            CreateFileW, FILE_FLAG_OVERLAPPED, OPEN_EXISTING,
        };
        use windows_sys::Win32::System::Threading::CreateEventW;

        let wide: Vec<u16> = name.encode_utf16().chain(std::iter::once(0)).collect();
        let deadline = Instant::now() + timeout;
        let handle = loop {
            let handle = unsafe {
                CreateFileW(
                    wide.as_ptr(),
                    GENERIC_READ | GENERIC_WRITE,
                    0,
                    null(),
                    OPEN_EXISTING,
                    FILE_FLAG_OVERLAPPED,
                    null_mut(),
                )
            };
            if handle != INVALID_HANDLE_VALUE {
                break handle;
            }
            let error = std::io::Error::last_os_error();
            let code = error.raw_os_error().unwrap_or(0) as u32;
            if code != ERROR_PIPE_BUSY && code != ERROR_FILE_NOT_FOUND {
                return Err(PipeError::Io(error));
            }
            if Instant::now() >= deadline {
                return Err(PipeError::Timeout);
            }
            thread::sleep(Duration::from_millis(25));
        };

        let cancel_event = unsafe { CreateEventW(null(), 1, 0, null_mut()) };
        if cancel_event.is_null() {
            let error = std::io::Error::last_os_error();
            unsafe {
                CloseHandle(handle);
            }
            return Err(PipeError::Io(error));
        }

        Ok(Self {
            handle,
            cancel_event,
            write_lock: std::sync::Mutex::new(()),
            read_lock: std::sync::Mutex::new(()),
            closed: std::sync::atomic::AtomicBool::new(false),
        })
    }

    fn cancel(&self) {
        use std::ptr::null;
        use std::sync::atomic::Ordering;
        use windows_sys::Win32::System::Threading::SetEvent;
        use windows_sys::Win32::System::IO::CancelIoEx;

        if self.closed.swap(true, Ordering::AcqRel) {
            return;
        }
        unsafe {
            SetEvent(self.cancel_event);
            CancelIoEx(self.handle, null());
        }
    }

    /// 取消所有挂起 I/O 并标记连接关闭；最后一个 `Arc` 释放时关闭句柄。
    fn close(&self) {
        self.cancel();
    }

    fn read_chunk(&self, buf: &mut [u8], timeout: Duration) -> Result<usize, PipeError> {
        use std::ptr::null_mut;
        use windows_sys::Win32::Foundation::{ERROR_IO_PENDING, WAIT_OBJECT_0, WAIT_TIMEOUT};
        use windows_sys::Win32::Storage::FileSystem::ReadFile;
        use windows_sys::Win32::System::Threading::{CreateEventW, WaitForMultipleObjects};
        use windows_sys::Win32::System::IO::{GetOverlappedResult, OVERLAPPED};

        let _guard = self
            .read_lock
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        if self.closed.load(std::sync::atomic::Ordering::Acquire) {
            return Err(PipeError::Cancelled);
        }
        if buf.is_empty() {
            return Ok(0);
        }

        // 内核在 overlapped I/O 真正完成前仍会访问缓冲区。使用堆缓冲区，
        // 取消超时时连同 OVERLAPPED/事件一起泄漏，绝不释放仍被内核引用的内存。
        let read_len = buf.len().min(u32::MAX as usize);
        let mut owned = vec![0u8; read_len].into_boxed_slice();
        let event = unsafe { CreateEventW(null_mut(), 0, 0, null_mut()) };
        if event.is_null() {
            return Err(PipeError::Io(std::io::Error::last_os_error()));
        }
        let mut overlapped: Box<OVERLAPPED> = Box::new(unsafe { std::mem::zeroed() });
        overlapped.hEvent = event;

        let ok = unsafe {
            ReadFile(
                self.handle,
                owned.as_mut_ptr(),
                owned.len() as u32,
                null_mut(),
                &mut *overlapped,
            )
        };
        if ok != 0 {
            let mut transferred = 0u32;
            let completed =
                unsafe { GetOverlappedResult(self.handle, &*overlapped, &mut transferred, 0) };
            unsafe {
                windows_sys::Win32::Foundation::CloseHandle(event);
            }
            if completed == 0 {
                return Err(PipeError::Io(std::io::Error::last_os_error()));
            }
            let amount = (transferred as usize).min(buf.len());
            buf[..amount].copy_from_slice(&owned[..amount]);
            return Ok(amount);
        }

        let error = std::io::Error::last_os_error();
        if error.raw_os_error().unwrap_or(0) as u32 != ERROR_IO_PENDING {
            unsafe {
                windows_sys::Win32::Foundation::CloseHandle(event);
            }
            return Err(PipeError::Io(error));
        }

        let handles = [event, self.cancel_event];
        let wait = unsafe {
            WaitForMultipleObjects(
                handles.len() as u32,
                handles.as_ptr(),
                0,
                duration_millis(timeout),
            )
        };
        match wait {
            WAIT_OBJECT_0 => {
                let mut transferred = 0u32;
                let ok =
                    unsafe { GetOverlappedResult(self.handle, &*overlapped, &mut transferred, 0) };
                unsafe {
                    windows_sys::Win32::Foundation::CloseHandle(event);
                }
                if ok != 0 {
                    let amount = (transferred as usize).min(buf.len());
                    buf[..amount].copy_from_slice(&owned[..amount]);
                    Ok(amount)
                } else {
                    Err(PipeError::Io(std::io::Error::last_os_error()))
                }
            }
            value if value == WAIT_OBJECT_0 + 1 => {
                cancel_and_reap(self.handle, event, overlapped, owned);
                Err(PipeError::Cancelled)
            }
            WAIT_TIMEOUT => {
                cancel_and_reap(self.handle, event, overlapped, owned);
                Err(PipeError::Timeout)
            }
            _ => {
                cancel_and_reap(self.handle, event, overlapped, owned);
                Err(PipeError::Io(std::io::Error::last_os_error()))
            }
        }
    }

    fn write_all(&self, data: &[u8], timeout: Duration) -> Result<(), PipeError> {
        use std::ptr::null_mut;
        use windows_sys::Win32::Foundation::{ERROR_IO_PENDING, WAIT_OBJECT_0, WAIT_TIMEOUT};
        use windows_sys::Win32::Storage::FileSystem::WriteFile;
        use windows_sys::Win32::System::Threading::{CreateEventW, WaitForSingleObject};
        use windows_sys::Win32::System::IO::{GetOverlappedResult, OVERLAPPED};

        let _guard = self
            .write_lock
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        if self.closed.load(std::sync::atomic::Ordering::Acquire) {
            return Err(PipeError::Cancelled);
        }

        let deadline = Instant::now() + timeout;
        let mut written_total = 0usize;
        while written_total < data.len() {
            let remaining = &data[written_total..];
            let chunk_len = remaining.len().min(u32::MAX as usize);
            // 与读取相同：写缓冲区也必须在取消确认前保持有效。
            let owned = remaining[..chunk_len].to_vec().into_boxed_slice();
            let event = unsafe { CreateEventW(null_mut(), 0, 0, null_mut()) };
            if event.is_null() {
                return Err(PipeError::Io(std::io::Error::last_os_error()));
            }
            let mut overlapped: Box<OVERLAPPED> = Box::new(unsafe { std::mem::zeroed() });
            overlapped.hEvent = event;

            let ok = unsafe {
                WriteFile(
                    self.handle,
                    owned.as_ptr(),
                    owned.len() as u32,
                    null_mut(),
                    &mut *overlapped,
                )
            };
            if ok != 0 {
                let mut transferred = 0u32;
                let completed =
                    unsafe { GetOverlappedResult(self.handle, &*overlapped, &mut transferred, 0) };
                unsafe {
                    windows_sys::Win32::Foundation::CloseHandle(event);
                }
                if completed == 0 {
                    return Err(PipeError::Io(std::io::Error::last_os_error()));
                }
                if transferred == 0 {
                    return Err(PipeError::Io(std::io::Error::new(
                        std::io::ErrorKind::WriteZero,
                        "mpv IPC 写入 0 字节",
                    )));
                }
                written_total += transferred as usize;
                continue;
            }

            let error = std::io::Error::last_os_error();
            if error.raw_os_error().unwrap_or(0) as u32 != ERROR_IO_PENDING {
                unsafe {
                    windows_sys::Win32::Foundation::CloseHandle(event);
                }
                return Err(PipeError::Io(error));
            }

            let remaining_time = deadline.saturating_duration_since(Instant::now());
            let wait = unsafe { WaitForSingleObject(event, duration_millis(remaining_time)) };
            match wait {
                WAIT_OBJECT_0 => {
                    let mut transferred = 0u32;
                    let ok = unsafe {
                        GetOverlappedResult(self.handle, &*overlapped, &mut transferred, 0)
                    };
                    unsafe {
                        windows_sys::Win32::Foundation::CloseHandle(event);
                    }
                    if ok == 0 {
                        return Err(PipeError::Io(std::io::Error::last_os_error()));
                    }
                    if transferred == 0 {
                        return Err(PipeError::Io(std::io::Error::new(
                            std::io::ErrorKind::WriteZero,
                            "mpv IPC 写入 0 字节",
                        )));
                    }
                    written_total += transferred as usize;
                }
                WAIT_TIMEOUT => {
                    cancel_and_reap(self.handle, event, overlapped, owned);
                    return Err(PipeError::Timeout);
                }
                _ => {
                    cancel_and_reap(self.handle, event, overlapped, owned);
                    return Err(PipeError::Io(std::io::Error::last_os_error()));
                }
            }
        }
        Ok(())
    }
}

#[cfg(windows)]
impl Drop for AsyncPipe {
    fn drop(&mut self) {
        use windows_sys::Win32::Foundation::CloseHandle;
        self.cancel();
        unsafe {
            CloseHandle(self.cancel_event);
            CloseHandle(self.handle);
        }
    }
}

/// 取消一次未完成的 overlapped I/O，并有界等待内核回收。
/// 极端情况下内核未及时完成取消时泄漏缓冲区/OVERLAPPED/事件，避免释放仍被
/// 内核引用的内存；句柄随后由连接关闭回收。
#[cfg(windows)]
fn cancel_and_reap(
    handle: windows_sys::Win32::Foundation::HANDLE,
    event: windows_sys::Win32::Foundation::HANDLE,
    overlapped: Box<windows_sys::Win32::System::IO::OVERLAPPED>,
    buffer: Box<[u8]>,
) {
    use windows_sys::Win32::Foundation::{CloseHandle, WAIT_OBJECT_0};
    use windows_sys::Win32::System::Threading::WaitForSingleObject;
    use windows_sys::Win32::System::IO::{CancelIoEx, GetOverlappedResult};

    unsafe {
        CancelIoEx(handle, &*overlapped);
    }
    let wait = unsafe { WaitForSingleObject(event, 2_000) };
    if wait == WAIT_OBJECT_0 {
        let mut transferred = 0u32;
        unsafe {
            GetOverlappedResult(handle, &*overlapped, &mut transferred, 0);
            CloseHandle(event);
        }
    } else {
        // 取消请求不等于操作已完成：缓冲区、OVERLAPPED 和事件都必须继续有效，
        // 直到内核真正完成。宁可泄漏这一小块内存，也不能让内核写已释放内存。
        let _ = Box::leak(overlapped);
        let _ = Box::leak(buffer);
    }
}

#[cfg(windows)]
fn duration_millis(duration: Duration) -> u32 {
    let millis = duration.as_millis();
    if millis == 0 {
        1
    } else {
        millis.min(u32::MAX as u128) as u32
    }
}

// ---------------------------------------------------------------------- Windows 作业对象

/// 保证应用退出时 mpv 不会残留。子进程加入 `KILL_ON_JOB_CLOSE` 作业；
/// 即使工作线程被卡住，关闭作业句柄或显式 `terminate()` 都会终止它。
#[cfg(windows)]
struct JobObject {
    handle: windows_sys::Win32::Foundation::HANDLE,
}

#[cfg(windows)]
unsafe impl Send for JobObject {}
#[cfg(windows)]
unsafe impl Sync for JobObject {}

#[cfg(windows)]
impl JobObject {
    fn new() -> Option<Self> {
        use std::mem::size_of;
        use std::ptr::null;
        use windows_sys::Win32::Foundation::CloseHandle;
        use windows_sys::Win32::System::JobObjects::{
            CreateJobObjectW, JobObjectExtendedLimitInformation, SetInformationJobObject,
            JOBOBJECT_EXTENDED_LIMIT_INFORMATION, JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE,
        };

        let handle = unsafe { CreateJobObjectW(null(), null()) };
        if handle.is_null() {
            return None;
        }
        let mut info: JOBOBJECT_EXTENDED_LIMIT_INFORMATION = unsafe { std::mem::zeroed() };
        info.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
        let ok = unsafe {
            SetInformationJobObject(
                handle,
                JobObjectExtendedLimitInformation,
                &info as *const _ as *const std::ffi::c_void,
                size_of::<JOBOBJECT_EXTENDED_LIMIT_INFORMATION>() as u32,
            )
        };
        if ok == 0 {
            unsafe {
                CloseHandle(handle);
            }
            return None;
        }
        Some(Self { handle })
    }

    fn assign(&self, child: &std::process::Child) -> bool {
        use std::os::windows::io::AsRawHandle;
        use windows_sys::Win32::System::JobObjects::AssignProcessToJobObject;

        unsafe { AssignProcessToJobObject(self.handle, child.as_raw_handle() as _) != 0 }
    }

    fn terminate(&self) {
        use windows_sys::Win32::System::JobObjects::TerminateJobObject;

        unsafe {
            TerminateJobObject(self.handle, 1);
        }
    }
}

#[cfg(windows)]
impl Drop for JobObject {
    fn drop(&mut self) {
        use windows_sys::Win32::Foundation::CloseHandle;

        unsafe {
            CloseHandle(self.handle);
        }
    }
}

// ---------------------------------------------------------------------- Windows 工作线程

#[cfg(windows)]
fn worker_loop(
    mpv_path: PathBuf,
    command_rx: Receiver<PlayerCommand>,
    event_tx: Sender<EmbeddedEvent>,
    job: Option<Arc<JobObject>>,
) {
    let mut worker = MpvWorker::new(mpv_path, command_rx, event_tx, job);
    worker.run();
}

#[cfg(windows)]
struct MpvWorker {
    mpv_path: PathBuf,
    command_rx: Receiver<PlayerCommand>,
    event_tx: Sender<EmbeddedEvent>,
    raw_tx: Sender<RawEvent>,
    raw_rx: Receiver<RawEvent>,
    job: Option<Arc<JobObject>>,
    hwnd: Option<isize>,
    child: Option<std::process::Child>,
    pipe: Option<Arc<AsyncPipe>>,
    reader_done: Option<Receiver<()>>,
    current_load_id: u64,
    volume: u8,
    pipe_seq: u64,
    last_time_pos_sent: f64,
    last_time_pos_at: Instant,
    /// 上一次清理未能在上限内确认旧进程退出；此时禁止启动新会话。
    cleanup_failed: bool,
}

#[cfg(windows)]
impl MpvWorker {
    fn new(
        mpv_path: PathBuf,
        command_rx: Receiver<PlayerCommand>,
        event_tx: Sender<EmbeddedEvent>,
        job: Option<Arc<JobObject>>,
    ) -> Self {
        let (raw_tx, raw_rx) = mpsc::channel();
        Self {
            mpv_path,
            command_rx,
            event_tx,
            raw_tx,
            raw_rx,
            job,
            hwnd: None,
            child: None,
            pipe: None,
            reader_done: None,
            current_load_id: 0,
            volume: 80,
            pipe_seq: 0,
            last_time_pos_sent: -1.0,
            last_time_pos_at: Instant::now(),
            cleanup_failed: false,
        }
    }

    fn run(&mut self) {
        loop {
            match self.command_rx.recv_timeout(Duration::from_millis(50)) {
                Ok(PlayerCommand::Shutdown { ack }) => {
                    self.cleanup_process();
                    if let Some(ack) = ack {
                        let _ = ack.send(());
                    }
                    break;
                }
                Ok(command) => self.handle_command(command),
                Err(mpsc::RecvTimeoutError::Timeout) => {}
                Err(mpsc::RecvTimeoutError::Disconnected) => {
                    self.cleanup_process();
                    break;
                }
            }
            self.drain_raw_events();
            self.check_process_exit();
        }
    }

    fn handle_command(&mut self, command: PlayerCommand) {
        match command {
            PlayerCommand::SetHwnd(hwnd) => {
                if self.hwnd != Some(hwnd) {
                    self.cleanup_process();
                    if self.cleanup_failed {
                        return;
                    }
                    self.hwnd = Some(hwnd);
                }
            }
            PlayerCommand::Load { load_id, options } => self.handle_load(load_id, options),
            PlayerCommand::Pause(paused) => {
                if let Err(error) = self.send_command(serde_json::json!({
                    "command": ["set_property", "pause", paused]
                })) {
                    self.handle_io_error(error);
                }
            }
            PlayerCommand::Volume(volume) => {
                self.volume = volume.min(100);
                if let Err(error) = self.send_command(serde_json::json!({
                    "command": ["set_property", "volume", self.volume]
                })) {
                    self.handle_io_error(error);
                }
            }
            PlayerCommand::Seek(seconds) => {
                if !seconds.is_finite() || seconds < 0.0 {
                    return;
                }
                if let Err(error) = self.send_command(serde_json::json!({
                    "command": ["seek", seconds, "absolute"]
                })) {
                    self.handle_io_error(error);
                }
            }
            PlayerCommand::Stop => self.cleanup_process(),
            PlayerCommand::Shutdown { .. } => {}
        }
    }

    fn handle_load(&mut self, load_id: u64, options: LoadOptions) {
        // 先完整释放旧进程，再启动新进程；两者不会同时操作同一个视频子窗口。
        self.cleanup_process();
        if self.cleanup_failed {
            self.send_event(EmbeddedEvent::Error {
                load_id,
                message: "旧播放器进程未能在上限内退出，已取消启动新会话".to_owned(),
            });
            return;
        }
        if let Err(error) = self.ensure_process(load_id, &options) {
            self.send_event(EmbeddedEvent::Error {
                load_id,
                message: error,
            });
            return;
        }
        self.current_load_id = load_id;
        self.last_time_pos_sent = -1.0;
        self.last_time_pos_at = Instant::now();
        if let Err(error) = self.observe_properties() {
            self.handle_io_error(error);
            return;
        }
        if let Err(error) = self.send_command(serde_json::json!({
            "command": ["set_property", "pause", false]
        })) {
            self.handle_io_error(error);
            return;
        }
        if let Err(error) = self.send_command(serde_json::json!({
            "command": ["loadfile", options.url, "replace"]
        })) {
            self.handle_io_error(error);
            return;
        }
        self.send_event(EmbeddedEvent::Loading { load_id });
    }

    fn ensure_process(&mut self, load_id: u64, options: &LoadOptions) -> Result<(), String> {
        let hwnd = self.hwnd.ok_or_else(|| "视频窗口尚未就绪".to_owned())?;
        let pipe_name = pipe_name(std::process::id(), self.pipe_seq);
        self.pipe_seq = self.pipe_seq.wrapping_add(1);

        use std::os::windows::process::CommandExt;
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        let args = build_mpv_args(hwnd, &pipe_name, options, self.volume);
        let mut command = std::process::Command::new(&self.mpv_path);
        command
            .args(args)
            .stdin(std::process::Stdio::null())
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null())
            .creation_flags(CREATE_NO_WINDOW);
        let child = command
            .spawn()
            .map_err(|error| format!("启动 mpv 失败：{error}"))?;
        if let Some(job) = &self.job {
            let _ = job.assign(&child);
        }

        let pipe = match AsyncPipe::open(&pipe_name, Duration::from_secs(3)) {
            Ok(pipe) => Arc::new(pipe),
            Err(error) => {
                let mut child = child;
                if !terminate_child_bounded(&mut child, Duration::from_millis(500)) {
                    self.child = Some(child);
                    self.cleanup_failed = true;
                }
                return Err(format!("连接 mpv IPC 失败：{error}"));
            }
        };
        let reader_done = match spawn_pipe_reader(pipe.clone(), self.raw_tx.clone(), load_id) {
            Ok(done) => done,
            Err(error) => {
                pipe.close();
                let mut child = child;
                if !terminate_child_bounded(&mut child, Duration::from_millis(500)) {
                    self.child = Some(child);
                    self.cleanup_failed = true;
                }
                return Err(format!("启动 mpv IPC 读取线程失败：{error}"));
            }
        };

        self.child = Some(child);
        self.pipe = Some(pipe);
        self.reader_done = Some(reader_done);
        Ok(())
    }

    fn observe_properties(&self) -> Result<(), String> {
        for (id, name) in [
            (1, "time-pos"),
            (2, "duration"),
            (3, "pause"),
            (4, "volume"),
        ] {
            self.send_command(serde_json::json!({
                "command": ["observe_property", id, name]
            }))?;
        }
        Ok(())
    }

    fn send_command(&self, value: serde_json::Value) -> Result<(), String> {
        let pipe = self.pipe.as_ref().ok_or_else(|| "mpv 未启动".to_owned())?;
        let mut line = serde_json::to_string(&value).map_err(|error| error.to_string())?;
        line.push('\n');
        pipe.write_all(line.as_bytes(), Duration::from_millis(500))
            .map_err(|error| error.to_string())
    }

    fn handle_io_error(&mut self, error: String) {
        let load_id = self.current_load_id;
        self.cleanup_process();
        if load_id != 0 {
            self.send_event(EmbeddedEvent::Error {
                load_id,
                message: error,
            });
        }
    }

    fn send_event(&self, event: EmbeddedEvent) {
        let _ = self.event_tx.send(event);
    }

    fn drain_raw_events(&mut self) {
        while let Ok(raw) = self.raw_rx.try_recv() {
            if !raw_event_is_current(self.current_load_id, &raw) {
                continue;
            }
            let load_id = raw.load_id;
            match raw.event {
                MpvRawEvent::StartFile => {
                    self.send_event(EmbeddedEvent::Loading { load_id });
                }
                MpvRawEvent::FileLoaded => {
                    self.send_event(EmbeddedEvent::Playing { load_id });
                }
                MpvRawEvent::EndFile { reason, error } => {
                    // redirect 不是会话结束：mpv 会继续加载新的 URL，不能清空当前 load_id。
                    if reason != EndReason::Redirect {
                        self.send_event(EmbeddedEvent::Ended {
                            load_id,
                            reason,
                            error,
                        });
                        self.current_load_id = 0;
                    }
                }
                MpvRawEvent::Property(property) => {
                    // time-pos 通常每帧变化；限制到约 4 次/秒，降低 UI 和 IPC 负担。
                    if let PlayerProperty::TimePos(value) = property {
                        let changed = (value - self.last_time_pos_sent).abs() >= 0.25;
                        let aged = self.last_time_pos_at.elapsed() >= Duration::from_millis(500);
                        if !changed && !aged {
                            continue;
                        }
                        self.last_time_pos_sent = value;
                        self.last_time_pos_at = Instant::now();
                        self.send_event(EmbeddedEvent::Property {
                            load_id,
                            property: PlayerProperty::TimePos(value),
                        });
                    } else {
                        self.send_event(EmbeddedEvent::Property { load_id, property });
                    }
                }
                MpvRawEvent::Idle | MpvRawEvent::PipeClosed => {}
            }
        }
    }

    fn check_process_exit(&mut self) {
        let exited = match self.child.as_mut() {
            Some(child) => match child.try_wait() {
                Ok(Some(status)) => Some(status),
                _ => None,
            },
            None => None,
        };
        let Some(status) = exited else {
            return;
        };
        let load_id = self.current_load_id;
        self.child = None;
        self.close_pipe_and_reader();
        self.current_load_id = 0;
        self.cleanup_failed = false;
        if load_id != 0 {
            self.send_event(EmbeddedEvent::ProcessExited {
                load_id,
                code: status.code(),
            });
        }
    }

    fn cleanup_process(&mut self) {
        self.current_load_id = 0;
        self.cleanup_failed = false;
        if self.pipe.is_some() {
            let _ = self.send_command(serde_json::json!({
                "command": ["quit"]
            }));
        }
        if let Some(pipe) = self.pipe.as_ref() {
            pipe.close();
        }
        self.close_pipe_and_reader();
        let Some(mut child) = self.child.take() else {
            return;
        };
        if terminate_child_bounded(&mut child, Duration::from_millis(700)) {
            return;
        }
        // 旧进程在正常上限内没有退出：用作业对象强制终止，再等一小段。
        if let Some(job) = &self.job {
            job.terminate();
        }
        if terminate_child_bounded(&mut child, Duration::from_millis(500)) {
            return;
        }
        // 仍无法确认退出：保留句柄供下次重试，并禁止启动新会话，避免两个
        // 进程同时操作同一个视频子窗口。应用退出时作业对象会再次兜底。
        self.child = Some(child);
        self.cleanup_failed = true;
    }

    fn close_pipe_and_reader(&mut self) {
        if let Some(pipe) = self.pipe.take() {
            pipe.close();
        }
        if let Some(done) = self.reader_done.take() {
            // 有界等待读取线程退出；不在 UI 线程，也绝不无限 join。
            let _ = done.recv_timeout(Duration::from_millis(300));
        }
    }
}

/// 先请求退出，再强制终止；所有等待都有上限。
/// 返回是否在超时前确认进程退出。
#[cfg(windows)]
fn terminate_child_bounded(child: &mut std::process::Child, timeout: Duration) -> bool {
    let _ = child.kill();
    let deadline = Instant::now() + timeout;
    loop {
        match child.try_wait() {
            Ok(Some(_)) => return true,
            _ if Instant::now() >= deadline => return false,
            _ => thread::sleep(Duration::from_millis(20)),
        }
    }
}

#[cfg(windows)]
fn spawn_pipe_reader(
    pipe: Arc<AsyncPipe>,
    raw_tx: Sender<RawEvent>,
    load_id: u64,
) -> Result<Receiver<()>, String> {
    let (done_tx, done_rx) = mpsc::channel();
    thread::Builder::new()
        .name("embedded-mpv-ipc".to_owned())
        .spawn(move || {
            let mut pending = Vec::new();
            let mut chunk = [0u8; 4096];
            loop {
                match pipe.read_chunk(&mut chunk, Duration::from_millis(1_000)) {
                    Ok(0) => break,
                    Ok(read) => {
                        pending.extend_from_slice(&chunk[..read]);
                        while let Some(position) = pending.iter().position(|&byte| byte == b'\n') {
                            let line: Vec<u8> = pending.drain(..=position).collect();
                            let text = String::from_utf8_lossy(&line[..line.len() - 1]);
                            if let Some(event) = parse_mpv_line(&text) {
                                let _ = raw_tx.send(RawEvent { load_id, event });
                            }
                        }
                        if pending.len() > 1_048_576 {
                            pending.clear();
                        }
                    }
                    Err(PipeError::Timeout) => continue,
                    Err(PipeError::Cancelled) => break,
                    Err(_) => break,
                }
            }
            let _ = raw_tx.send(RawEvent {
                load_id,
                event: MpvRawEvent::PipeClosed,
            });
            let _ = done_tx.send(());
        })
        .map_err(|error| error.to_string())?;
    Ok(done_rx)
}

#[cfg(test)]
fn test_worker_loop(command_rx: Receiver<PlayerCommand>, delay: Duration) {
    while let Ok(command) = command_rx.recv() {
        thread::sleep(delay);
        if let PlayerCommand::Shutdown { ack } = command {
            if let Some(ack) = ack {
                let _ = ack.send(());
            }
            break;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Instant;

    #[test]
    fn mpv_args_include_embedding_ipc_and_network_options() {
        let options = LoadOptions {
            url: "http://example.com/live.m3u8".to_owned(),
            user_agent: "HMVideo/2.0".to_owned(),
            referer: "http://example.com/".to_owned(),
            disable_hw_decode: true,
        };
        let args = build_mpv_args(1234, r"\\.\pipe\hmvod-test", &options, 70);
        let args: Vec<String> = args
            .into_iter()
            .map(|arg| arg.to_string_lossy().into_owned())
            .collect();
        assert!(args.contains(&"--wid=1234".to_owned()));
        assert!(args.contains(&r"--input-ipc-server=\\.\pipe\hmvod-test".to_owned()));
        assert!(args.contains(&"--hwdec=no".to_owned()));
        assert!(args.contains(&"--user-agent=HMVideo/2.0".to_owned()));
        assert!(args.contains(&"--referrer=http://example.com/".to_owned()));
        assert!(args.contains(&"--volume=70".to_owned()));
    }

    #[test]
    fn raw_events_keep_their_producing_session() {
        let old = RawEvent {
            load_id: 1,
            event: MpvRawEvent::EndFile {
                reason: EndReason::Error,
                error: None,
            },
        };
        let new = RawEvent {
            load_id: 2,
            event: MpvRawEvent::FileLoaded,
        };
        assert!(!raw_event_is_current(2, &old));
        assert!(raw_event_is_current(1, &old));
        assert!(raw_event_is_current(2, &new));
        assert!(!raw_event_is_current(1, &new));
    }

    #[test]
    fn builds_windows_named_pipe_path() {
        assert_eq!(pipe_name(1234, 7), r"\\.\pipe\hmvod-1234-7".to_owned());
    }

    #[test]
    fn parses_mpv_events() {
        assert_eq!(
            parse_mpv_line(r#"{"event":"file-loaded"}"#),
            Some(MpvRawEvent::FileLoaded)
        );
        assert_eq!(
            parse_mpv_line(r#"{"event":"end-file","reason":"eof"}"#),
            Some(MpvRawEvent::EndFile {
                reason: EndReason::Eof,
                error: None,
            })
        );
        assert_eq!(
            parse_mpv_line(r#"{"event":"end-file","reason":"error","error":"failed"}"#),
            Some(MpvRawEvent::EndFile {
                reason: EndReason::Error,
                error: Some("failed".to_owned()),
            })
        );
        assert_eq!(
            parse_mpv_line(r#"{"event":"property-change","name":"time-pos","data":12.5}"#),
            Some(MpvRawEvent::Property(PlayerProperty::TimePos(12.5)))
        );
        assert_eq!(
            parse_mpv_line(r#"{"event":"property-change","name":"duration","data":null}"#),
            Some(MpvRawEvent::Property(PlayerProperty::Duration(None)))
        );
        assert_eq!(
            parse_mpv_line(r#"{"event":"property-change","name":"pause","data":true}"#),
            Some(MpvRawEvent::Property(PlayerProperty::Pause(true)))
        );
        assert_eq!(parse_mpv_line(r#"{"event":"property-change"}"#), None);
    }

    #[test]
    fn embedded_backend_is_unavailable_without_runtime() {
        if cfg!(not(windows)) {
            assert!(!available());
            assert!(matches!(
                EmbeddedPlayer::new(),
                Err(EmbeddedError::Unsupported)
            ));
        }
    }

    #[test]
    fn finds_bundled_mpv_layouts() {
        let root = std::env::temp_dir().join(format!(
            "hmvod-mpv-test-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap_or_default()
                .as_nanos()
        ));
        let _ = std::fs::remove_dir_all(&root);
        let mpv_dir = root.join("mpv");
        std::fs::create_dir_all(&mpv_dir).expect("create mpv dir");
        let expected = mpv_dir.join("mpv.exe");
        std::fs::write(&expected, b"test").expect("write mpv");

        assert_eq!(
            find_mpv_in_roots(std::slice::from_ref(&root)),
            Some(expected.clone())
        );
        assert_eq!(
            find_mpv_in_roots(&[root.join("missing"), root.clone()]),
            Some(expected)
        );
        assert_eq!(find_mpv_in_roots(&[root.join("missing")]), None);
        let _ = std::fs::remove_dir_all(root);
    }

    #[test]
    fn video_window_stub_is_safe_on_non_windows() {
        if cfg!(not(windows)) {
            let mut window = VideoWindow::new();
            assert_eq!(window.hwnd(), 0);
            window.ensure(1).expect("stub ensure");
            window.set_bounds(Some((0, 0, 10, 10)));
            window.hide();
            window.destroy();
        }
    }

    #[test]
    fn commands_return_without_waiting_for_slow_worker() {
        let mut player = EmbeddedPlayer::new_test_slow(Duration::from_millis(400));
        let start = Instant::now();
        player.set_volume(55);
        player.pause(true);
        player.stop();
        assert!(
            start.elapsed() < Duration::from_millis(100),
            "UI-facing commands blocked on worker: {:?}",
            start.elapsed()
        );

        let start = Instant::now();
        player.shutdown_fast();
        assert!(
            start.elapsed() < Duration::from_millis(100),
            "shutdown_fast blocked on worker: {:?}",
            start.elapsed()
        );
    }

    #[test]
    fn shutdown_blocking_is_bounded_when_worker_is_slow() {
        let mut player = EmbeddedPlayer::new_test_slow(Duration::from_millis(400));
        let start = Instant::now();
        player.shutdown_blocking(Duration::from_millis(100));
        let elapsed = start.elapsed();
        assert!(
            elapsed < Duration::from_millis(300),
            "shutdown_blocking exceeded bound: {elapsed:?}"
        );
    }

    #[cfg(windows)]
    #[test]
    fn overlapped_pipe_supports_concurrent_read_write_cancel_and_close() {
        use std::sync::mpsc;
        use std::sync::Arc;

        let name = format!(
            r"\\.\pipe\hmvod-pipe-test-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap_or_default()
                .as_nanos()
        );
        let (server, stop_server) = spawn_test_pipe_server(&name);
        let pipe =
            Arc::new(AsyncPipe::open(&name, Duration::from_secs(3)).expect("open test pipe"));

        let (line_tx, line_rx) = mpsc::channel();
        let (reader_done_tx, reader_done_rx) = mpsc::channel();
        let reader_pipe = pipe.clone();
        let reader = thread::spawn(move || {
            let mut pending = Vec::new();
            let mut chunk = [0u8; 1024];
            loop {
                match reader_pipe.read_chunk(&mut chunk, Duration::from_secs(2)) {
                    Ok(0) => break,
                    Ok(read) => {
                        pending.extend_from_slice(&chunk[..read]);
                        while let Some(position) = pending.iter().position(|&byte| byte == b'\n') {
                            let line: Vec<u8> = pending.drain(..=position).collect();
                            let text = String::from_utf8_lossy(&line[..line.len() - 1]);
                            let _ = line_tx.send(text.into_owned());
                        }
                    }
                    Err(PipeError::Timeout) => continue,
                    Err(PipeError::Cancelled) => break,
                    Err(_) => break,
                }
            }
            let _ = reader_done_tx.send(());
        });

        // 服务端在收到命令前保持静默；客户端读取线程已阻塞，写入仍应立即返回。
        pipe.write_all(
            b"{\"command\":[\"get_property\",\"pause\"]}\n",
            Duration::from_secs(2),
        )
        .expect("write command while read is pending");
        let response = line_rx
            .recv_timeout(Duration::from_secs(3))
            .expect("receive response while server was silent");
        assert_eq!(response, r#"{"event":"file-loaded"}"#);

        // 服务端继续保持静默；取消挂起的读取，读取线程应退出，句柄可关闭。
        pipe.close();
        assert!(
            reader_done_rx.recv_timeout(Duration::from_secs(3)).is_ok(),
            "cancelled reader did not stop"
        );
        let _ = reader.join();
        drop(pipe);
        let _ = stop_server.send(());
        server.join().expect("test pipe server thread");
    }

    #[cfg(windows)]
    fn spawn_test_pipe_server(name: &str) -> (thread::JoinHandle<()>, Sender<()>) {
        use std::ptr::{null, null_mut};
        use windows_sys::Win32::Foundation::{
            CloseHandle, ERROR_PIPE_CONNECTED, INVALID_HANDLE_VALUE,
        };
        use windows_sys::Win32::Storage::FileSystem::{ReadFile, WriteFile, PIPE_ACCESS_DUPLEX};
        use windows_sys::Win32::System::Pipes::{
            ConnectNamedPipe, CreateNamedPipeW, PIPE_READMODE_BYTE, PIPE_TYPE_BYTE,
            PIPE_UNLIMITED_INSTANCES, PIPE_WAIT,
        };

        let wide: Vec<u16> = name.encode_utf16().chain(std::iter::once(0)).collect();
        let (stop_tx, stop_rx) = mpsc::channel();
        let server = thread::spawn(move || {
            let pipe = unsafe {
                CreateNamedPipeW(
                    wide.as_ptr(),
                    PIPE_ACCESS_DUPLEX,
                    PIPE_TYPE_BYTE | PIPE_READMODE_BYTE | PIPE_WAIT,
                    PIPE_UNLIMITED_INSTANCES,
                    4096,
                    4096,
                    0,
                    null(),
                )
            };
            assert_ne!(pipe, INVALID_HANDLE_VALUE, "CreateNamedPipeW failed");
            let connected = unsafe { ConnectNamedPipe(pipe, null_mut()) };
            if connected == 0 {
                let error = std::io::Error::last_os_error();
                assert_eq!(
                    error.raw_os_error().unwrap_or(0) as u32,
                    ERROR_PIPE_CONNECTED,
                    "ConnectNamedPipe failed: {error}"
                );
            }

            let mut command = Vec::new();
            let mut chunk = [0u8; 512];
            while !command.contains(&b'\n') {
                let mut read = 0u32;
                let ok = unsafe {
                    ReadFile(
                        pipe,
                        chunk.as_mut_ptr(),
                        chunk.len() as u32,
                        &mut read,
                        null_mut(),
                    )
                };
                assert_ne!(ok, 0, "server ReadFile failed");
                if read == 0 {
                    break;
                }
                command.extend_from_slice(&chunk[..read as usize]);
            }
            assert!(
                command.windows(7).any(|window| window == b"command"),
                "server did not receive command: {:?}",
                String::from_utf8_lossy(&command)
            );

            let response = b"{\"event\":\"file-loaded\"}\n";
            let mut written = 0u32;
            let ok = unsafe {
                WriteFile(
                    pipe,
                    response.as_ptr(),
                    response.len() as u32,
                    &mut written,
                    null_mut(),
                )
            };
            assert_ne!(ok, 0, "server WriteFile failed");

            // 保持连接但不再发送数据，验证客户端在静默期仍可并发写入。
            let _ = stop_rx.recv_timeout(Duration::from_secs(10));
            unsafe {
                CloseHandle(pipe);
            }
        });
        (server, stop_tx)
    }
}
