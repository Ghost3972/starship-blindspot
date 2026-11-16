#!/usr/bin/env python3
"""生成 EVCam+补盲技术方案文档的所有图表"""

import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import matplotlib.patches as mpatches
from matplotlib.patches import FancyBboxPatch, FancyArrowPatch, ArrowStyle
import numpy as np
import os

# ── 全局设置 ──
plt.rcParams['font.family'] = ['PingFang HK', 'Heiti TC', 'STHeiti', 'Songti SC', 'Hiragino Sans', 'sans-serif']
plt.rcParams['axes.unicode_minus'] = False

OUT = os.path.dirname(os.path.abspath(__file__))
DPI = 180

def save(fig, name):
    fig.savefig(os.path.join(OUT, name), dpi=DPI, bbox_inches='tight',
                facecolor=fig.get_facecolor(), edgecolor='none', pad_inches=0.3)
    plt.close(fig)
    print(f"  ✓ {name}")


# ════════════════════════════════════════════════════════════
# 图 1: 系统架构思维导图
# ════════════════════════════════════════════════════════════
def gen_mindmap():
    fig, ax = plt.subplots(figsize=(14, 9), facecolor='white')
    ax.set_xlim(0, 14)
    ax.set_ylim(0, 9)
    ax.axis('off')

    def box(x, y, w, h, text, color, fontsize=9, fc='white', bold=False):
        bx = FancyBboxPatch((x - w/2, y - h/2), w, h,
                            boxstyle="round,pad=0.15", facecolor=color, edgecolor='none', alpha=0.92)
        ax.add_patch(bx)
        fw = 'bold' if bold else 'normal'
        ax.text(x, y, text, ha='center', va='center', fontsize=fontsize,
                color=fc, fontweight=fw, zorder=5)

    def arrow(x1, y1, x2, y2, color='#999', lw=1.5, style='-'):
        ax.annotate('', xy=(x2, y2), xytext=(x1, y1),
                    arrowprops=dict(arrowstyle='->', color=color, lw=lw, linestyle=style))

    # 中心
    box(7, 8.2, 4, 0.6, 'EVCam + 补盲系统', '#0f3460', fontsize=14, fc='white', bold=True)

    # ── 左分支: 补盲 ──
    arrow(5.5, 8.0, 3.5, 7.2, '#e94560', 2)
    box(3.5, 7.2, 3.2, 0.5, '补盲服务 (com.elliot.blindspot)', '#e94560', 11, 'white', True)

    arrow(2.5, 6.95, 1.8, 6.3, '#e94560')
    box(1.8, 6.3, 2.2, 0.4, 'Camera2 相机控制', '#ff6b6b', 9, 'white')

    arrow(3.5, 6.95, 3.5, 6.0, '#e94560')
    box(3.5, 6.0, 2.6, 0.4, 'CameraGLRenderer', '#ff6b6b', 9, 'white')

    # GL 子节点
    arrow(2.7, 5.8, 1.5, 5.2, '#ffab91')
    box(1.5, 5.2, 1.6, 0.35, 'EGL 上下文', '#ffa07a', 8, 'white')
    arrow(3.5, 5.8, 3.5, 5.0, '#ffab91')
    box(3.5, 5.0, 2.0, 0.35, 'GLSL Shader 裁切', '#ffa07a', 8, 'white')
    arrow(4.3, 5.8, 5.5, 5.2, '#ffab91')
    box(5.5, 5.2, 1.6, 0.35, 'glReadPixels', '#ffa07a', 8, 'white')

    arrow(4.5, 6.95, 5.2, 6.3, '#e94560')
    box(5.2, 6.3, 2.0, 0.4, '转向灯监听', '#ff6b6b', 9, 'white')
    arrow(5.2, 6.1, 5.2, 4.3, '#ffab91')
    box(5.2, 4.3, 2.0, 0.35, 'Car API / Logcat', '#ffa07a', 8, 'white')

    arrow(2.2, 6.95, 1.5, 3.8, '#e94560')
    box(1.5, 3.8, 2.6, 0.4, 'VirtualCameraProvider', '#ff6b6b', 9, 'white')
    arrow(1.0, 3.6, 0.8, 3.0, '#ffab91')
    box(0.8, 3.0, 1.8, 0.35, 'SharedMemory', '#ffa07a', 8, 'white')
    arrow(2.0, 3.6, 2.5, 3.0, '#ffab91')
    box(2.5, 3.0, 1.8, 0.35, 'JPEG 文件', '#ffa07a', 8, 'white')

    # ── 右分支: EVCam ──
    arrow(8.5, 8.0, 10.5, 7.2, '#2196F3', 2)
    box(10.5, 7.2, 3.0, 0.5, 'EVCam (com.kooo.evcam)', '#2196F3', 11, 'white', True)

    arrow(9.5, 6.95, 9.0, 6.3, '#2196F3')
    box(9.0, 6.3, 2.6, 0.4, 'MultiCameraManager', '#42a5f5', 9, 'white')
    arrow(8.5, 6.1, 8.0, 5.5, '#90caf9')
    box(8.0, 5.5, 2.0, 0.35, 'SingleCamera ×4', '#64b5f6', 8, 'white')
    arrow(9.5, 6.1, 10.0, 5.5, '#90caf9')
    box(10.0, 5.5, 2.2, 0.35, 'CodecVideoRecorder', '#64b5f6', 8, 'white')

    arrow(11.0, 6.95, 12.0, 6.3, '#2196F3')
    box(12.0, 6.3, 2.8, 0.4, 'StitchedCameraRenderer', '#42a5f5', 9, 'white')
    arrow(12.0, 6.1, 12.0, 5.5, '#90caf9')
    box(12.0, 5.5, 2.2, 0.35, 'Canvas 裁切4象限', '#64b5f6', 8, 'white')

    arrow(10.5, 6.95, 10.5, 4.8, '#2196F3')
    box(10.5, 4.8, 2.8, 0.4, 'BlindspotRecordingProxy', '#42a5f5', 9, 'white')
    arrow(10.5, 4.6, 10.5, 4.0, '#90caf9')
    box(10.5, 4.0, 2.0, 0.35, '广播控制录制', '#64b5f6', 8, 'white')

    arrow(11.5, 6.95, 12.5, 4.8, '#2196F3')
    box(12.5, 4.8, 2.2, 0.4, 'EglSurfaceEncoder', '#42a5f5', 9, 'white')
    arrow(12.5, 4.6, 12.5, 4.0, '#90caf9')
    box(12.5, 4.0, 2.0, 0.35, 'OES→MediaCodec', '#64b5f6', 8, 'white')

    # ── 中间: IPC ──
    arrow(3.0, 2.8, 6.5, 2.0, '#4caf50', 1.5, '--')
    arrow(10.0, 3.8, 7.5, 2.0, '#4caf50', 1.5, '--')
    box(7.0, 2.0, 2.8, 0.5, 'IPC 跨进程通信', '#4caf50', 10, 'white', True)

    arrow(6.0, 1.75, 5.0, 1.2, '#81c784')
    box(5.0, 1.2, 2.2, 0.35, 'SharedMemory', '#66bb6a', 8, 'white')
    arrow(7.0, 1.75, 7.0, 1.2, '#81c784')
    box(7.0, 1.2, 1.8, 0.35, 'JPEG 文件', '#66bb6a', 8, 'white')
    arrow(8.0, 1.75, 9.0, 1.2, '#81c784')
    box(9.0, 1.2, 2.0, 0.35, '广播录制控制', '#66bb6a', 8, 'white')

    # 底部输出
    box(7.0, 0.4, 5.5, 0.5, '输出：4路预览 + 全景录制 + 盲区浮窗', '#ff9800', 11, 'white', True)
    arrow(5.0, 1.0, 5.5, 0.65, '#ff9800')
    arrow(7.0, 1.0, 7.0, 0.65, '#ff9800')
    arrow(9.0, 1.0, 8.5, 0.65, '#ff9800')

    ax.set_title('图 1-1：EVCam + 补盲系统整体架构思维导图', fontsize=11, color='#666', pad=10)
    save(fig, 'fig01_mindmap.png')


# ════════════════════════════════════════════════════════════
# 图 2: 协同架构（生产者-消费者）
# ════════════════════════════════════════════════════════════
def gen_architecture():
    fig, ax = plt.subplots(figsize=(13, 8), facecolor='white')
    ax.set_xlim(0, 13)
    ax.set_ylim(0, 8)
    ax.axis('off')

    def box(x, y, w, h, text, color, fontsize=9, fc='white', bold=False, alpha=0.92):
        bx = FancyBboxPatch((x - w/2, y - h/2), w, h,
                            boxstyle="round,pad=0.12", facecolor=color, edgecolor='none', alpha=alpha)
        ax.add_patch(bx)
        fw = 'bold' if bold else 'normal'
        ax.text(x, y, text, ha='center', va='center', fontsize=fontsize,
                color=fc, fontweight=fw, zorder=5)

    def arrow(x1, y1, x2, y2, color='#999', lw=1.5, style='-'):
        ax.annotate('', xy=(x2, y2), xytext=(x1, y1),
                    arrowprops=dict(arrowstyle='->', color=color, lw=lw, linestyle=style))

    # 补盲区域背景
    bg = FancyBboxPatch((0.3, 0.5), 5.5, 7, boxstyle="round,pad=0.2",
                        facecolor='#fff0f0', edgecolor='#e94560', lw=2)
    ax.add_patch(bg)
    ax.text(3.0, 7.3, '补盲服务（生产者）', ha='center', fontsize=13, color='#e94560', fontweight='bold')

    # EVCam区域背景
    bg2 = FancyBboxPatch((7.2, 0.5), 5.5, 7, boxstyle="round,pad=0.2",
                         facecolor='#e3f2fd', edgecolor='#2196F3', lw=2)
    ax.add_patch(bg2)
    ax.text(10.0, 7.3, 'EVCam（消费者）', ha='center', fontsize=13, color='#2196F3', fontweight='bold')

    # 补盲内部
    box(3.0, 6.6, 2.5, 0.45, 'camera0 (2560×1600)', '#333', 10, '#0f0', True)
    arrow(3.0, 6.35, 3.0, 6.0, '#7c3aed', 2)
    box(3.0, 5.8, 3.5, 0.4, 'GL SurfaceTexture (OES 纹理)', '#7c3aed', 9, 'white')
    arrow(3.0, 5.6, 3.0, 5.2, '#7c3aed', 2)
    box(3.0, 5.0, 4.0, 0.45, 'CameraGLRenderer (GPU 渲染线程)', '#e94560', 10, 'white', True)

    # 3路输出
    arrow(1.8, 4.75, 1.5, 4.2, '#e94560', 1.5)
    arrow(3.0, 4.75, 3.0, 4.2, '#e94560', 1.5)
    arrow(4.2, 4.75, 4.5, 4.2, '#e94560', 1.5)

    box(1.5, 3.8, 1.6, 0.7, '① 预览\nEGL Surface\n→ 浮窗', '#ff6b6b', 8, 'white')
    box(3.0, 3.8, 1.6, 0.7, '② 录制\nMediaCodec\n→ MP4', '#ff6b6b', 8, 'white')
    box(4.5, 3.8, 1.6, 0.7, '③ 帧共享\nglReadPixels\n→ SharedMem', '#ff6b6b', 8, 'white')

    arrow(4.5, 3.4, 4.5, 2.8, '#e94560')
    box(4.5, 2.5, 2.2, 0.45, 'AtomicRef 异步写入', '#e94560', 8, 'white', alpha=0.7)
    arrow(4.5, 2.25, 4.5, 1.8, '#e94560')
    box(3.5, 1.5, 2.8, 0.45, 'JPEG 文件 / SharedMemory', '#e94560', 8, 'white', alpha=0.5)

    # EVCam内部
    box(10.0, 6.2, 3.5, 0.45, 'StitchedCameraRenderer', '#2196F3', 10, 'white', True)
    arrow(10.0, 5.95, 10.0, 5.5, '#2196F3')
    box(10.0, 5.3, 3.2, 0.4, '读取 JPEG → Canvas 裁切 4 象限', '#42a5f5', 9, 'white')

    for i, label in enumerate(['前', '后', '左', '右']):
        box(8.8 + i * 0.9, 4.6, 0.7, 0.4, label, '#64b5f6', 10, 'white')
    arrow(10.0, 5.1, 10.0, 4.85, '#2196F3')

    box(10.0, 3.5, 3.2, 0.45, 'BlindspotRecordingProxy', '#2196F3', 10, 'white', True)
    arrow(10.0, 3.25, 10.0, 2.8, '#2196F3')
    box(10.0, 2.6, 2.5, 0.4, '广播 → 补盲执行录制', '#42a5f5', 9, 'white')

    # IPC 连接
    arrow(5.0, 1.5, 8.5, 5.5, '#4caf50', 2.5, '--')
    ax.text(6.5, 3.8, '帧数据', ha='center', fontsize=10, color='#4caf50', fontweight='bold', rotation=40)

    arrow(8.5, 2.6, 5.0, 4.0, '#ff9800', 2, '--')
    ax.text(6.5, 2.8, '广播控制', ha='center', fontsize=9, color='#ff9800', fontweight='bold', rotation=-20)

    ax.set_title('图 2-1：补盲持有相机、EVCam 委托的协同架构', fontsize=11, color='#666', pad=10)
    save(fig, 'fig02_architecture.png')


# ════════════════════════════════════════════════════════════
# 图 3: GPU 管线流程图
# ════════════════════════════════════════════════════════════
def gen_gpu_pipeline():
    fig, ax = plt.subplots(figsize=(13, 9), facecolor='white')
    ax.set_xlim(0, 13)
    ax.set_ylim(0, 9)
    ax.axis('off')

    def box(x, y, w, h, text, color, fontsize=9, fc='white', bold=False):
        bx = FancyBboxPatch((x - w/2, y - h/2), w, h,
                            boxstyle="round,pad=0.1", facecolor=color, edgecolor='none', alpha=0.92)
        ax.add_patch(bx)
        fw = 'bold' if bold else 'normal'
        ax.text(x, y, text, ha='center', va='center', fontsize=fontsize,
                color=fc, fontweight=fw, zorder=5)

    def arrow(x1, y1, x2, y2, color='#999', lw=1.5):
        ax.annotate('', xy=(x2, y2), xytext=(x1, y1),
                    arrowprops=dict(arrowstyle='->', color=color, lw=lw))

    # 顶部
    box(6.5, 8.5, 3.5, 0.45, 'camera0 (2560×1600)', '#333', 11, '#0f0', True)
    arrow(6.5, 8.25, 6.5, 7.8, '#333', 2)
    box(6.5, 7.6, 4.5, 0.4, 'SurfaceTexture → GL_TEXTURE_EXTERNAL_OES', '#7c3aed', 9, 'white')
    arrow(6.5, 7.4, 6.5, 7.0, '#7c3aed', 2)
    box(6.5, 6.8, 4.0, 0.4, 'updateTexImage() + getTransformMatrix()', '#9333ea', 9, 'white')

    # 三路分叉
    arrow(4.5, 6.6, 2.5, 6.0, '#e94560', 2)
    arrow(6.5, 6.6, 6.5, 6.0, '#e94560', 2)
    arrow(8.5, 6.6, 10.5, 6.0, '#e94560', 2)

    # ── 路径1: 帧共享 ──
    box(2.5, 5.8, 3.0, 0.4, '③ 帧共享 (PBuffer)', '#e94560', 10, 'white', True)
    steps_l = [
        ('eglMakeCurrent(PBuffer)', '#ff6b6b'),
        ('glUniform4f(crop, 0,0,1,1) 全景', '#ff6b6b'),
        ('glDrawArrays() → 1920×1200', '#ff6b6b'),
        ('glReadPixels() → ByteBuffer', '#d32f2f'),
        ('AtomicRef → 异步 SharedMemory', '#b71c1c'),
    ]
    for i, (txt, col) in enumerate(steps_l):
        y = 5.2 - i * 0.6
        arrow(2.5, y + 0.45, 2.5, y + 0.2, col)
        bold = 'ReadPixels' in txt or 'Atomic' in txt
        box(2.5, y, 3.2, 0.35, txt, col, 8, 'white', bold)

    # ── 路径2: 预览 ──
    box(6.5, 5.8, 3.0, 0.4, '① 预览 (WindowSurface)', '#e94560', 10, 'white', True)
    steps_m = [
        ('eglMakeCurrent(preview)', '#ff6b6b'),
        ('glUniform4f(crop, 盲区象限)', '#ff6b6b'),
        ('glDrawArrays(TRIANGLE_STRIP)', '#ff6b6b'),
        ('eglSwapBuffers(preview)', '#d32f2f'),
        ('→ 浮窗 TextureView', '#333'),
    ]
    for i, (txt, col) in enumerate(steps_m):
        y = 5.2 - i * 0.6
        arrow(6.5, y + 0.45, 6.5, y + 0.2, col)
        bold = 'SwapBuffers' in txt
        box(6.5, y, 3.0, 0.35, txt, col, 8, 'white', bold)

    # ── 路径3: 录制 ──
    box(10.5, 5.8, 3.0, 0.4, '② 录制 (CodecSurface)', '#e94560', 10, 'white', True)
    steps_r = [
        ('eglMakeCurrent(record)', '#ff6b6b'),
        ('glUniform4f(crop, 0,0,1,1) 全景', '#ff6b6b'),
        ('glDrawArrays() + 水印叠加', '#ff6b6b'),
        ('eglPresentationTimeANDROID()', '#d32f2f'),
        ('eglSwapBuffers(record) → H.264', '#b71c1c'),
    ]
    for i, (txt, col) in enumerate(steps_r):
        y = 5.2 - i * 0.6
        arrow(10.5, y + 0.45, 10.5, y + 0.2, col)
        bold = 'SwapBuffers' in txt or 'Presentation' in txt
        box(10.5, y, 3.2, 0.35, txt, col, 8, 'white', bold)

    ax.set_title('图 3-1：CameraGLRenderer 一帧内的 3 路 GPU 并行输出流程', fontsize=11, color='#666', pad=10)
    save(fig, 'fig03_gpu_pipeline.png')


# ════════════════════════════════════════════════════════════
# 图 4: 异步帧共享线程协同
# ════════════════════════════════════════════════════════════
def gen_async_frame_sharing():
    fig, ax = plt.subplots(figsize=(13, 6.5), facecolor='white')
    ax.set_xlim(0, 13)
    ax.set_ylim(0, 6.5)
    ax.axis('off')

    def box(x, y, w, h, text, color, fontsize=9, fc='white', bold=False):
        bx = FancyBboxPatch((x - w/2, y - h/2), w, h,
                            boxstyle="round,pad=0.1", facecolor=color, edgecolor='none', alpha=0.92)
        ax.add_patch(bx)
        fw = 'bold' if bold else 'normal'
        ax.text(x, y, text, ha='center', va='center', fontsize=fontsize,
                color=fc, fontweight=fw, zorder=5)

    def arrow(x1, y1, x2, y2, color='#999', lw=1.5, style='-'):
        ax.annotate('', xy=(x2, y2), xytext=(x1, y1),
                    arrowprops=dict(arrowstyle='->', color=color, lw=lw, linestyle=style))

    # GL 渲染线程区域
    bg = FancyBboxPatch((0.3, 0.3), 5.0, 5.8, boxstyle="round,pad=0.2",
                        facecolor='#f3e5f5', edgecolor='#7c3aed', lw=2)
    ax.add_patch(bg)
    ax.text(2.8, 5.9, 'GL 渲染线程 (60fps)', ha='center', fontsize=12, color='#7c3aed', fontweight='bold')
    ax.text(2.8, 5.6, 'THREAD_PRIORITY_URGENT_DISPLAY', ha='center', fontsize=8, color='#7c3aed')

    steps = [
        ('1. updateTexImage() — 0.1ms', '#9333ea', False),
        ('2. renderToSurface(PBuffer) — 0.3ms', '#9333ea', False),
        ('3. glReadPixels(1920×1200) — ~5ms', '#7c3aed', True),
        ('4. copyPixelsFromBuffer — ~3ms', '#7c3aed', False),
        ('5. pendingFrame.set(bitmap) — 0ms !!!', '#e94560', True),
        ('6. renderToSurface(preview) — 0.3ms', '#9333ea', False),
        ('7. renderToSurface(record) — 0.5ms', '#9333ea', False),
        ('8. 总计 ~2ms/帧 (不含readPixels)', '#6a1b9a', True),
    ]
    for i, (txt, col, bold) in enumerate(steps):
        y = 5.1 - i * 0.55
        box(2.8, y, 4.2, 0.38, txt, col, 8.5, 'white', bold)

    # AtomicReference
    box(6.5, 3.3, 1.2, 0.8, 'Atomic\nRef', '#e94560', 10, 'white', True)
    arrow(5.0, 3.3, 5.9, 3.3, '#e94560', 3)

    # 异步写入线程区域
    bg2 = FancyBboxPatch((7.7, 0.3), 5.0, 5.8, boxstyle="round,pad=0.2",
                         facecolor='#e8f5e9', edgecolor='#4caf50', lw=2)
    ax.add_patch(bg2)
    ax.text(10.2, 5.9, '异步写入线程 (125Hz 轮询)', ha='center', fontsize=12, color='#2e7d32', fontweight='bold')
    ax.text(10.2, 5.6, 'THREAD_PRIORITY_DISPLAY', ha='center', fontsize=8, color='#2e7d32')

    arrow(7.1, 3.3, 7.9, 4.8, '#4caf50', 2)

    steps2 = [
        ('1. frame = pendingFrame.getAndSet(null)', '#43a047', False),
        ('2. virtualCamera.writeFrame(frame)', '#43a047', False),
        ('3. → SharedMemory 双缓冲 (18MB)', '#388e3c', True),
        ('4. sharedFullFrame = frame', '#43a047', False),
        ('5. cropFrameForDirection → HUD', '#43a047', False),
        ('6. 写入 JPEG → EVCam 读取', '#2e7d32', True),
    ]
    for i, (txt, col, bold) in enumerate(steps2):
        y = 5.1 - i * 0.65
        box(10.2, y, 4.2, 0.4, txt, col, 8.5, 'white', bold)

    ax.set_title('图 4-1：GL 线程与异步写入线程的无锁协同', fontsize=11, color='#666', pad=10)
    save(fig, 'fig04_async_sharing.png')


# ════════════════════════════════════════════════════════════
# 图 5: 象限裁切示意图
# ════════════════════════════════════════════════════════════
def gen_quadrant():
    fig, axes = plt.subplots(1, 3, figsize=(13, 4.5), facecolor='white')

    # 全景
    ax = axes[0]
    ax.set_xlim(0, 4)
    ax.set_ylim(0, 2.5)
    ax.set_aspect('equal')
    ax.set_title('camera0 全景 (2560×1600)', fontsize=10, fontweight='bold')

    colors = ['#e3f2fd', '#fff8e1', '#e8f5e9', '#fce4ec']
    labels = ['前视角\n(0,0,0.5,0.5)', '后视角\n(0.5,0,1,0.5)',
              '左盲区\n(0,0.5,0.5,1)', '右盲区\n(0.5,0.5,1,1)']
    borders = ['#2196F3', '#ff9800', '#e94560', '#4caf50']
    coords = [(0, 1.25, 2, 1.25), (2, 1.25, 2, 1.25), (0, 0, 2, 1.25), (2, 0, 2, 1.25)]

    for i, (c, l, b, (cx, cy, cw, ch)) in enumerate(zip(colors, labels, borders, coords)):
        rect = plt.Rectangle((cx, cy), cw, ch, facecolor=c, edgecolor=b, lw=2)
        ax.add_patch(rect)
        ax.text(cx + cw/2, cy + ch/2, l, ha='center', va='center', fontsize=8, color=b, fontweight='bold')
    ax.axis('off')

    # 左转
    ax = axes[1]
    ax.set_xlim(0, 2.5)
    ax.set_ylim(0, 1.8)
    ax.set_aspect('equal')
    ax.set_title('左转 → 显示左盲区', fontsize=10, fontweight='bold', color='#e94560')
    rect = plt.Rectangle((0.1, 0.1), 2.3, 1.6, facecolor='#fce4ec', edgecolor='#e94560', lw=3)
    ax.add_patch(rect)
    ax.text(1.25, 0.9, '左盲区\nglUniform4f(crop,\n0, 0.5, 0.5, 1)', ha='center', va='center',
            fontsize=9, color='#e94560', fontweight='bold')
    ax.axis('off')

    # 右转
    ax = axes[2]
    ax.set_xlim(0, 2.5)
    ax.set_ylim(0, 1.8)
    ax.set_aspect('equal')
    ax.set_title('右转 → 显示右盲区', fontsize=10, fontweight='bold', color='#4caf50')
    rect = plt.Rectangle((0.1, 0.1), 2.3, 1.6, facecolor='#e8f5e9', edgecolor='#4caf50', lw=3)
    ax.add_patch(rect)
    ax.text(1.25, 0.9, '右盲区\nglUniform4f(crop,\n0.5, 0.5, 1, 1)', ha='center', va='center',
            fontsize=9, color='#4caf50', fontweight='bold')
    ax.axis('off')

    fig.suptitle('图 6-1：转向灯触发象限切换（Shader uniform 实时切换，零开销）', fontsize=11, color='#666', y=0.02)
    plt.tight_layout()
    save(fig, 'fig05_quadrant.png')


# ════════════════════════════════════════════════════════════
# 图 6: 性能对比柱状图
# ════════════════════════════════════════════════════════════
def gen_perf_chart():
    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(12, 5), facecolor='white')

    # 左图: 每帧耗时
    categories = ['获取帧', '象限裁切', '录制输入', '水印叠加', '总计']
    old_times = [20, 5, 3, 3, 30]
    new_times = [0.1, 0.1, 0.0, 0.5, 2]

    x = np.arange(len(categories))
    w = 0.35
    bars1 = ax1.bar(x - w/2, old_times, w, label='旧方案 (getBitmap)', color='#ff6b6b', edgecolor='white')
    bars2 = ax1.bar(x + w/2, new_times, w, label='新方案 (GL Pipeline)', color='#4caf50', edgecolor='white')

    ax1.set_ylabel('耗时 (ms)', fontsize=10)
    ax1.set_title('每帧耗时对比', fontsize=12, fontweight='bold')
    ax1.set_xticks(x)
    ax1.set_xticklabels(categories, fontsize=9)
    ax1.legend(fontsize=9)
    ax1.set_ylim(0, 35)

    for bar, val in zip(bars1, old_times):
        ax1.text(bar.get_x() + bar.get_width()/2., bar.get_height() + 0.5,
                f'{val}ms', ha='center', va='bottom', fontsize=8, color='#e94560')
    for bar, val in zip(bars2, new_times):
        ax1.text(bar.get_x() + bar.get_width()/2., bar.get_height() + 0.5,
                f'{val}ms', ha='center', va='bottom', fontsize=8, color='#2e7d32')

    # 右图: 帧率对比
    labels = ['旧方案\n(getBitmap+Canvas)', '新方案\n(GL Pipeline)']
    fps_values = [30, 60]
    colors = ['#ff6b6b', '#4caf50']
    bars = ax2.bar(labels, fps_values, color=colors, width=0.5, edgecolor='white')
    ax2.set_ylabel('帧率 (fps)', fontsize=10)
    ax2.set_title('帧率上限对比', fontsize=12, fontweight='bold')
    ax2.set_ylim(0, 75)

    for bar, val in zip(bars, fps_values):
        ax2.text(bar.get_x() + bar.get_width()/2., bar.get_height() + 1,
                f'{val} fps', ha='center', va='bottom', fontsize=14, fontweight='bold',
                color=bar.get_facecolor())

    ax2.axhline(y=60, color='#4caf50', linestyle='--', alpha=0.3, lw=1)
    ax2.text(1.35, 61, '60fps 目标', fontsize=8, color='#4caf50', alpha=0.5)

    plt.tight_layout()
    fig.suptitle('图 9-1：旧方案 vs 新方案性能对比（性能提升 15x）', fontsize=11, color='#666', y=-0.02)
    save(fig, 'fig06_performance.png')


# ════════════════════════════════════════════════════════════
# 图 7: 线程模型
# ════════════════════════════════════════════════════════════
def gen_thread_model():
    fig, ax = plt.subplots(figsize=(13, 5), facecolor='white')
    ax.set_xlim(0, 13)
    ax.set_ylim(0, 5)
    ax.axis('off')

    def thread_box(x, y, w, h, title, subtitle, items, color, light_color):
        bg = FancyBboxPatch((x, y), w, h, boxstyle="round,pad=0.15",
                           facecolor=light_color, edgecolor=color, lw=2)
        ax.add_patch(bg)
        ax.text(x + w/2, y + h - 0.25, title, ha='center', fontsize=10, color=color, fontweight='bold')
        ax.text(x + w/2, y + h - 0.55, subtitle, ha='center', fontsize=7, color=color)
        for i, item in enumerate(items):
            iy = y + h - 0.9 - i * 0.38
            ib = FancyBboxPatch((x + 0.1, iy - 0.12), w - 0.2, 0.3,
                               boxstyle="round,pad=0.05", facecolor=color, edgecolor='none', alpha=0.85)
            ax.add_patch(ib)
            ax.text(x + w/2, iy + 0.03, item, ha='center', fontsize=7.5, color='white')

    thread_box(0.2, 0.3, 2.2, 4.3,
               'GL 渲染线程', 'URGENT_DISPLAY',
               ['updateTexImage', 'renderToSurface ×3', 'glReadPixels', 'eglSwapBuffers', 'drawWatermark', '目标: 60fps'],
               '#7c3aed', '#f3e5f5')

    thread_box(2.7, 0.3, 2.2, 4.3,
               '异步写入线程', 'DISPLAY',
               ['取 pendingFrame', 'writeFrame', 'cropForHUD', '写 JPEG 文件', '更新 sharedFullFrame', '125Hz 轮询'],
               '#4caf50', '#e8f5e9')

    thread_box(5.2, 0.3, 2.2, 4.3,
               '编码器排空线程', 'DEFAULT',
               ['drainEncoder', 'writeSampleData', 'segmentSwitch', '分段切换', '', ''],
               '#e94560', '#fce4ec')

    thread_box(7.7, 0.3, 2.2, 4.3,
               '信号监听线程', 'DEFAULT',
               ['Car API 轮询', 'ECARX API', 'Logcat 解析', '方向变化回调', '', ''],
               '#ff9800', '#fff8e1')

    thread_box(10.2, 0.3, 2.5, 4.3,
               '主线程', 'DEFAULT',
               ['Service 生命周期', '浮窗管理', '广播接收', 'HUD/仪表盘投影', '', ''],
               '#2196F3', '#e3f2fd')

    ax.set_title('图 10-1：补盲服务线程模型', fontsize=11, color='#666', pad=10)
    save(fig, 'fig07_threads.png')


# ════════════════════════════════════════════════════════════
# 图 8: IPC 通信通道
# ════════════════════════════════════════════════════════════
def gen_ipc():
    fig, ax = plt.subplots(figsize=(12, 5.5), facecolor='white')
    ax.set_xlim(0, 12)
    ax.set_ylim(0, 5.5)
    ax.axis('off')

    def box(x, y, w, h, text, color, fontsize=9, fc='white', bold=False):
        bx = FancyBboxPatch((x - w/2, y - h/2), w, h,
                            boxstyle="round,pad=0.12", facecolor=color, edgecolor='none', alpha=0.92)
        ax.add_patch(bx)
        fw = 'bold' if bold else 'normal'
        ax.text(x, y, text, ha='center', va='center', fontsize=fontsize,
                color=fc, fontweight=fw, zorder=5)

    # 两端
    bg_l = FancyBboxPatch((0.5, 0.5), 3, 4.2, boxstyle="round,pad=0.2",
                          facecolor='#fce4ec', edgecolor='#e94560', lw=2)
    ax.add_patch(bg_l)
    ax.text(2.0, 4.5, '补盲服务', ha='center', fontsize=13, color='#e94560', fontweight='bold')

    bg_r = FancyBboxPatch((8.5, 0.5), 3, 4.2, boxstyle="round,pad=0.2",
                          facecolor='#e3f2fd', edgecolor='#2196F3', lw=2)
    ax.add_patch(bg_r)
    ax.text(10.0, 4.5, 'EVCam', ha='center', fontsize=13, color='#2196F3', fontweight='bold')

    # 通道
    channels = [
        ('SharedMemory (18MB 双缓冲)', '#4caf50', 4.0, '帧数据（已实现未启用）', '→'),
        ('JPEG 文件 (shared_frame.jpg)', '#ff9800', 3.2, '帧数据（当前使用）', '→'),
        ('广播 (START/STOP_RECORDING)', '#e94560', 2.4, '录制控制命令', '←'),
        ('AIDL (IVirtualCamera)', '#7c3aed', 1.6, 'Service 绑定 + 参数', '↔'),
    ]
    for txt, color, y, desc, direction in channels:
        box(6.0, y, 3.5, 0.4, txt, color, 9, 'white', True)
        ax.text(6.0, y - 0.32, desc, ha='center', fontsize=7.5, color=color, alpha=0.8)
        if direction == '→':
            ax.annotate('', xy=(4.3, y), xytext=(3.5, y),
                       arrowprops=dict(arrowstyle='->', color=color, lw=1.5))
            ax.annotate('', xy=(8.5, y), xytext=(7.7, y),
                       arrowprops=dict(arrowstyle='->', color=color, lw=1.5))
        elif direction == '←':
            ax.annotate('', xy=(3.5, y), xytext=(4.3, y),
                       arrowprops=dict(arrowstyle='->', color=color, lw=1.5))
            ax.annotate('', xy=(7.7, y), xytext=(8.5, y),
                       arrowprops=dict(arrowstyle='->', color=color, lw=1.5))
        else:
            ax.annotate('', xy=(4.3, y), xytext=(3.5, y),
                       arrowprops=dict(arrowstyle='<->', color=color, lw=1.5))
            ax.annotate('', xy=(8.5, y), xytext=(7.7, y),
                       arrowprops=dict(arrowstyle='<->', color=color, lw=1.5))

    ax.set_title('图 11-1：EVCam ↔ 补盲服务 IPC 通信通道', fontsize=11, color='#666', pad=10)
    save(fig, 'fig08_ipc.png')


# ════════════════════════════════════════════════════════════
# 主入口
# ════════════════════════════════════════════════════════════
if __name__ == '__main__':
    print("Generating all diagrams...")
    gen_mindmap()
    gen_architecture()
    gen_gpu_pipeline()
    gen_async_frame_sharing()
    gen_quadrant()
    gen_perf_chart()
    gen_thread_model()
    gen_ipc()
    print("Done! All images saved to:", OUT)
