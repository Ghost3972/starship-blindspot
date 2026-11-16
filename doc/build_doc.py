#!/usr/bin/env python3
"""将生成的 PNG 图表嵌入 HTML 格式的 .doc 文件"""

import base64
import os

DIR = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(os.path.dirname(DIR), 'EVCam_Blindspot_技术方案.doc')

def img_tag(filename, width_pct=95):
    path = os.path.join(DIR, filename)
    with open(path, 'rb') as f:
        b64 = base64.b64encode(f.read()).decode()
    return f'<img src="data:image/png;base64,{b64}" style="width:{width_pct}%; display:block; margin:15px auto;" />'

# 读取所有图片
imgs = {}
for name in ['fig01_mindmap.png', 'fig02_architecture.png', 'fig03_gpu_pipeline.png',
             'fig04_async_sharing.png', 'fig05_quadrant.png', 'fig06_performance.png',
             'fig07_threads.png', 'fig08_ipc.png']:
    imgs[name] = img_tag(name)
    print(f"  Encoded: {name}")

html = f'''<html xmlns:o="urn:schemas-microsoft-com:office:office"
      xmlns:w="urn:schemas-microsoft-com:office:word"
      xmlns="http://www.w3.org/TR/REC-html40">
<head>
<meta charset="utf-8">
<meta name="ProgId" content="Word.Document">
<meta name="Generator" content="Microsoft Word 15">
<meta name="Originator" content="Microsoft Word 15">
<title>EVCam + 补盲系统技术方案</title>
<!--[if gte mso 9]><xml>
<o:DocumentProperties>
  <o:Author>EVCam Team</o:Author>
</o:DocumentProperties>
<w:WordDocument>
  <w:View>Print</w:View>
  <w:Zoom>100</w:Zoom>
</w:WordDocument>
</xml><![endif]-->
<style>
  @page {{ size: A4; margin: 2cm 2.5cm; }}
  body {{
    font-family: "Microsoft YaHei", "微软雅黑", "Segoe UI", Arial, sans-serif;
    font-size: 11pt; line-height: 1.8; color: #1a1a2e;
  }}
  .cover {{ text-align: center; padding: 100px 0 60px 0; page-break-after: always; }}
  .cover h1 {{ font-size: 26pt; color: #0f3460; margin-bottom: 8px; letter-spacing: 3px; }}
  .cover .subtitle {{ font-size: 15pt; color: #16213e; margin-bottom: 50px; }}
  .cover .meta {{ font-size: 11pt; color: #555; line-height: 2.2; }}
  .cover .meta b {{ color: #333; }}
  .cover .line {{ width: 50%; margin: 30px auto; border: none; border-top: 3px solid #0f3460; }}

  h1 {{ font-size: 20pt; color: #0f3460; border-bottom: 3px solid #0f3460; padding-bottom: 6px; margin-top: 36px; page-break-before: always; }}
  h1.first {{ page-break-before: avoid; }}
  h2 {{ font-size: 15pt; color: #16213e; border-left: 5px solid #e94560; padding-left: 12px; margin-top: 24px; }}
  h3 {{ font-size: 12pt; color: #0f3460; margin-top: 16px; }}

  table {{ width: 100%; border-collapse: collapse; margin: 12px 0; font-size: 10pt; }}
  th {{ background: #0f3460; color: white; padding: 8px 10px; text-align: left; }}
  td {{ padding: 7px 10px; border: 1px solid #ddd; }}
  tr:nth-child(even) {{ background: #f8f9fa; }}

  code {{ font-family: Consolas, monospace; background: #f0f0f0; padding: 1px 5px; border-radius: 3px; font-size: 9.5pt; color: #c7254e; }}
  pre {{ background: #1e1e2e; color: #cdd6f4; padding: 14px 18px; border-radius: 8px; font-family: Consolas, monospace; font-size: 9pt; line-height: 1.6; white-space: pre-wrap; word-wrap: break-word; }}
  pre .kw {{ color: #cba6f7; }} pre .fn {{ color: #89b4fa; }} pre .str {{ color: #a6e3a1; }}
  pre .cmt {{ color: #6c7086; font-style: italic; }} pre .num {{ color: #fab387; }} pre .type {{ color: #f9e2af; }}

  .info-box {{ background: #e8f4fd; border-left: 4px solid #2196F3; padding: 10px 14px; margin: 12px 0; border-radius: 0 6px 6px 0; }}
  .warn-box {{ background: #fff8e1; border-left: 4px solid #ff9800; padding: 10px 14px; margin: 12px 0; border-radius: 0 6px 6px 0; }}
  .key-box {{ background: #fce4ec; border-left: 4px solid #e94560; padding: 10px 14px; margin: 12px 0; border-radius: 0 6px 6px 0; }}

  .toc {{ background: #f5f7fa; padding: 18px 28px; border-radius: 8px; margin: 16px 0; }}
  .toc h2 {{ border: none; padding: 0; margin-top: 0; }}
  .toc ol {{ line-height: 2.2; }}

  .diagram {{ text-align: center; margin: 20px 0; }}
  .diagram img {{ border: 1px solid #e0e0e0; border-radius: 8px; }}

  .highlight {{ background: #ffd54f; padding: 0 4px; }}
  ul, ol {{ margin: 6px 0; }} li {{ margin: 3px 0; }}
</style>
</head>
<body>

<!-- 封面 -->
<div class="cover">
  <h1>EVCam + 补盲系统</h1>
  <div class="subtitle">技术方案详细设计文档</div>
  <hr class="line">
  <div class="meta">
    <b>版本：</b>v2.0<br>
    <b>日期：</b>2026年2月10日<br>
    <b>分类：</b>内部技术文档 / 架构设计<br>
    <b>关键技术：</b>OpenGL ES 2.0 · Camera2 API · MediaCodec · SharedMemory · AIDL<br>
  </div>
</div>

<!-- 目录 -->
<div class="toc">
  <h2>目 录</h2>
  <ol>
    <li>系统架构总览</li>
    <li>核心矛盾与解决方案</li>
    <li>补盲服务 GPU 渲染管线（OpenGL 详解）</li>
    <li>帧共享机制与高帧率保证</li>
    <li>EVCam 预览与录制控制</li>
    <li>转向灯检测与动态切换</li>
    <li>OpenGL 调用完整清单</li>
    <li>GLSL Shader 源码</li>
    <li>性能对比与优化总结</li>
    <li>线程模型</li>
    <li>IPC 通信机制</li>
  </ol>
</div>


<!-- ═══ 第1章 ═══ -->
<h1 class="first">一、系统架构总览</h1>

<h2>1.1 系统组成</h2>
<table>
  <tr><th>模块</th><th>包名</th><th>职责</th><th>核心技术</th></tr>
  <tr><td><b>EVCam</b></td><td><code>com.kooo.evcam</code></td><td>多路摄像头录制（最多4路）、UI 管理</td><td>Camera2, MediaRecorder/Codec</td></tr>
  <tr><td><b>补盲</b></td><td><code>com.elliot.blindspot</code></td><td>全景相机处理、转向灯检测、盲区叠加</td><td>OpenGL ES 2.0, EGL, SharedMemory</td></tr>
</table>

<h2>1.2 系统架构思维导图</h2>
<div class="diagram">{imgs['fig01_mindmap.png']}</div>


<!-- ═══ 第2章 ═══ -->
<h1>二、核心矛盾与解决方案</h1>

<h2>2.1 资源独占问题</h2>
<div class="key-box">
  <b>核心矛盾：</b><code>camera0</code>（全景拼接摄像头，2560×1600）是<b>独占资源</b>。一个进程打开后，另一个进程无法同时使用。补盲需要它显示盲区画面，EVCam 需要它录制四路视频。<b>两者不能同时直接打开 camera0</b>。
</div>

<h2>2.2 解决方案：补盲持有相机，EVCam 委托</h2>
<div class="diagram">{imgs['fig02_architecture.png']}</div>

<div class="info-box">
  <b>设计原则：</b>补盲服务独占 <code>camera0</code>，同时承担预览、录制、帧共享三项工作。EVCam 通过读取补盲共享的帧数据实现预览，通过广播委托补盲执行录制。两个应用永远不会同时竞争 camera0。
</div>


<!-- ═══ 第3章 ═══ -->
<h1>三、补盲服务 GPU 渲染管线（OpenGL 详解）</h1>

<h2>3.1 GPU 管线流程图</h2>
<div class="diagram">{imgs['fig03_gpu_pipeline.png']}</div>

<h2>3.2 EGL 初始化调用序列</h2>
<table>
  <tr><th>步骤</th><th>API 调用</th><th>说明</th></tr>
  <tr><td>1</td><td><code>EGL14.eglGetDisplay(EGL_DEFAULT_DISPLAY)</code></td><td>获取默认 EGL Display</td></tr>
  <tr><td>2</td><td><code>EGL14.eglInitialize(display, ver, 0, ver, 1)</code></td><td>初始化 EGL</td></tr>
  <tr><td>3</td><td><code>EGL14.eglChooseConfig(..., EGL_RECORDABLE_ANDROID=1)</code></td><td>选择支持录制的配置</td></tr>
  <tr><td>4</td><td><code>EGL14.eglCreateContext(..., GLES2)</code></td><td>创建 OpenGL ES 2.0 上下文</td></tr>
  <tr><td>5</td><td><code>EGL14.eglCreateWindowSurface(previewSurface)</code></td><td>预览输出 Surface</td></tr>
  <tr><td>6</td><td><code>EGL14.eglCreateWindowSurface(codecInputSurface)</code></td><td>录制输出 Surface</td></tr>
  <tr><td>7</td><td><code>EGL14.eglCreatePbufferSurface(1920×1200)</code></td><td>离屏帧共享 Surface</td></tr>
  <tr><td>8</td><td><code>EGL14.eglMakeCurrent(surface)</code></td><td>激活上下文到指定 Surface</td></tr>
</table>

<h2>3.3 OpenGL 纹理与着色器初始化</h2>
<table>
  <tr><th>步骤</th><th>API 调用</th><th>说明</th></tr>
  <tr><td>1</td><td><code>GLES20.glGenTextures(1, texId, 0)</code></td><td>生成纹理 ID</td></tr>
  <tr><td>2</td><td><code>GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, id)</code></td><td>绑定外部 OES 纹理</td></tr>
  <tr><td>3</td><td><code>GLES20.glTexParameteri(MIN_FILTER=LINEAR)</code></td><td>设置纹理参数</td></tr>
  <tr><td>4</td><td><code>new SurfaceTexture(textureId)</code></td><td>创建 SurfaceTexture</td></tr>
  <tr><td>5</td><td><code>GLES20.glCreateShader + glCompileShader</code></td><td>编译着色器</td></tr>
  <tr><td>6</td><td><code>GLES20.glCreateProgram + glLinkProgram</code></td><td>链接着色器程序</td></tr>
  <tr><td>7</td><td><code>GLES20.glGetAttribLocation("aPosition")</code></td><td>获取属性位置</td></tr>
  <tr><td>8</td><td><code>GLES20.glGetUniformLocation("uCropRect")</code></td><td>获取裁切区域 uniform</td></tr>
</table>

<h2>3.4 每帧渲染调用（renderToSurface）</h2>
<table>
  <tr><th>步骤</th><th>OpenGL 调用</th><th>说明</th></tr>
  <tr><td>1</td><td><code>eglMakeCurrent(targetSurface)</code></td><td>切换到目标 EGL Surface</td></tr>
  <tr><td>2</td><td><code>glViewport(0, 0, width, height)</code></td><td>设置视口大小</td></tr>
  <tr><td>3</td><td><code>glClear(GL_COLOR_BUFFER_BIT)</code></td><td>清除颜色缓冲</td></tr>
  <tr><td>4</td><td><code>glUseProgram(programId)</code></td><td>激活着色器程序</td></tr>
  <tr><td>5</td><td><code>glActiveTexture(GL_TEXTURE0)</code></td><td>激活纹理单元 0</td></tr>
  <tr><td>6</td><td><code>glBindTexture(GL_TEXTURE_EXTERNAL_OES, id)</code></td><td>绑定相机 OES 纹理</td></tr>
  <tr><td>7</td><td><code>glUniformMatrix4fv(mvpMatrix)</code></td><td>设置 MVP 矩阵</td></tr>
  <tr><td>8</td><td><code>glUniformMatrix4fv(stMatrix)</code></td><td>设置纹理变换矩阵</td></tr>
  <tr><td>9</td><td><code>glUniform4f(cropRect, L, T, R, B)</code></td><td><b>设置象限裁切区域</b></td></tr>
  <tr><td>10</td><td><code>glVertexAttribPointer + glEnableVertexAttribArray</code></td><td>设置顶点属性</td></tr>
  <tr><td>11</td><td><code>glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)</code></td><td>绘制全屏四边形</td></tr>
  <tr><td>12</td><td><code>eglSwapBuffers(surface)</code></td><td>提交渲染结果</td></tr>
</table>


<!-- ═══ 第4章 ═══ -->
<h1>四、帧共享机制与高帧率保证</h1>

<h2>4.1 异步帧共享架构</h2>
<div class="key-box">
  <b>核心设计：</b>GL 线程从不等待 SharedMemory 写入完成。通过 <code>AtomicReference&lt;Bitmap&gt;</code> 实现"最新帧覆盖"策略。如果异步线程来不及消费，新帧直接覆盖旧帧，GL 线程不会被拖慢。
</div>

<div class="diagram">{imgs['fig04_async_sharing.png']}</div>

<h2>4.2 SharedMemory 双缓冲结构</h2>
<table>
  <tr><th>区域</th><th>偏移量</th><th>大小</th><th>内容</th></tr>
  <tr><td>Header</td><td>0</td><td>64 bytes</td><td>宽度(4B) + 高度(4B) + 缓冲区数(4B) + 帧大小(4B) + 帧号(8B) + 时间戳(8B) + 当前索引(4B)</td></tr>
  <tr><td>Buffer 0</td><td>64</td><td>9,216,000 bytes</td><td>1920 × 1200 × 4 (ARGB_8888)</td></tr>
  <tr><td>Buffer 1</td><td>9,216,064</td><td>9,216,000 bytes</td><td>1920 × 1200 × 4 (ARGB_8888)</td></tr>
  <tr><td colspan="2"><b>总计</b></td><td colspan="2"><b>~18.4 MB</b></td></tr>
</table>


<!-- ═══ 第5章 ═══ -->
<h1>五、EVCam 预览与录制控制</h1>

<h2>5.1 EVCam 预览流程</h2>
<p>补盲服务运行时，EVCam 的 <code>StitchedCameraRenderer</code> 自动切换到"共享画面模式"：</p>
<table>
  <tr><th>步骤</th><th>操作</th><th>技术手段</th><th>耗时</th></tr>
  <tr><td>1</td><td>检测文件更新</td><td><code>file.lastModified()</code> 比对</td><td>&lt;0.1ms</td></tr>
  <tr><td>2</td><td>解码 JPEG</td><td><code>BitmapFactory.decodeFile()</code></td><td>~5ms</td></tr>
  <tr><td>3</td><td>裁切 4 象限</td><td><code>Canvas.drawBitmap(srcRect, dstRect)</code></td><td>~1ms ×4</td></tr>
  <tr><td>4</td><td>显示到 TextureView</td><td><code>lockCanvas → drawBitmap → unlockCanvasAndPost</code></td><td>~1ms ×4</td></tr>
</table>
<p>轮询间隔 33ms（30fps），EVCam 预览帧率上限约 30fps。</p>

<h2>5.2 录制控制</h2>
<p>EVCam 通过 <code>BlindspotRecordingProxy</code> 发送广播控制补盲服务录制：</p>
<table>
  <tr><th>广播 Action</th><th>方向</th><th>用途</th></tr>
  <tr><td><code>com.elliot.blindspot.START_RECORDING</code></td><td>EVCam → 补盲</td><td>开始录制</td></tr>
  <tr><td><code>com.elliot.blindspot.STOP_RECORDING</code></td><td>EVCam → 补盲</td><td>停止录制</td></tr>
  <tr><td><code>com.elliot.blindspot.RECORDING_STATUS</code></td><td>补盲 → EVCam</td><td>录制状态回报</td></tr>
</table>


<!-- ═══ 第6章 ═══ -->
<h1>六、转向灯检测与动态切换</h1>

<h2>6.1 转向灯检测方式</h2>
<table>
  <tr><th>方式</th><th>API</th><th>优先级</th><th>说明</th></tr>
  <tr><td>Android Car API</td><td><code>CarPropertyManager.getIntProperty(TURN_SIGNAL_STATE)</code></td><td>最高</td><td>标准 AAOS</td></tr>
  <tr><td>ECARX API</td><td><code>car.ecarx.turnSignal</code></td><td>中</td><td>吉利/领克定制</td></tr>
  <tr><td>Logcat 解析</td><td>监听 <code>VehicleHal</code> 日志</td><td>兜底</td><td>通用方案</td></tr>
</table>

<h2>6.2 象限裁切示意图</h2>
<div class="diagram">{imgs['fig05_quadrant.png']}</div>

<div class="info-box">
  <b>关键优化：</b>预览裁切和录制裁切<b>完全独立</b>。预览跟随转向灯切换象限，录制始终保持全景。切换只修改 <code>glUniform4f(cropRectHandle, ...)</code> 的值，在 shader 中通过 <code>mix()</code> 完成，零开销。
</div>


<!-- ═══ 第7章 ═══ -->
<h1>七、OpenGL 调用完整清单</h1>

<h2>7.1 EVCam vs 补盲 OpenGL 调用对照</h2>
<table>
  <tr><th>OpenGL/EGL API</th><th>EVCam (EglSurfaceEncoder)</th><th>补盲 (CameraGLRenderer)</th><th>说明</th></tr>
  <tr><td><code>eglGetDisplay</code></td><td>&#10004;</td><td>&#10004;</td><td>获取 Display</td></tr>
  <tr><td><code>eglInitialize</code></td><td>&#10004;</td><td>&#10004;</td><td>初始化 EGL</td></tr>
  <tr><td><code>eglChooseConfig (RECORDABLE)</code></td><td>&#10004;</td><td>&#10004;</td><td>录制配置</td></tr>
  <tr><td><code>eglCreateContext (GLES2)</code></td><td>&#10004;</td><td>&#10004;</td><td>GLES2 上下文</td></tr>
  <tr><td><code>eglCreateWindowSurface</code></td><td>&#10004; (1个:录制)</td><td>&#10004; (2个:预览+录制)</td><td>窗口 Surface</td></tr>
  <tr><td><code>eglCreatePbufferSurface</code></td><td>—</td><td>&#10004; (1920×1200)</td><td>离屏帧共享</td></tr>
  <tr><td><code>eglMakeCurrent</code></td><td>&#10004;</td><td>&#10004; (频繁切换3个)</td><td>切换 Surface</td></tr>
  <tr><td><code>eglSwapBuffers</code></td><td>&#10004;</td><td>&#10004;</td><td>提交渲染</td></tr>
  <tr><td><code>eglPresentationTimeANDROID</code></td><td>&#10004;</td><td>&#10004;</td><td>帧时间戳</td></tr>
  <tr><td><code>glGenTextures</code></td><td>&#10004; (OES)</td><td>&#10004; (OES+2D)</td><td>生成纹理</td></tr>
  <tr><td><code>glCreateShader + Compile</code></td><td>&#10004;</td><td>&#10004;</td><td>编译着色器</td></tr>
  <tr><td><code>glCreateProgram + Link</code></td><td>&#10004; (1~2个)</td><td>&#10004; (2个)</td><td>链接程序</td></tr>
  <tr><td><code>glUniform4f (uCropRect)</code></td><td>—</td><td><b>&#10004; 象限裁切</b></td><td>补盲核心</td></tr>
  <tr><td><code>glUniform4f (uWatermarkRect)</code></td><td>&#10004;</td><td>—</td><td>水印区域</td></tr>
  <tr><td><code>GLUtils.texImage2D</code></td><td>&#10004;</td><td>&#10004;</td><td>Bitmap→纹理</td></tr>
  <tr><td><code>glEnable(GL_BLEND)</code></td><td>— (shader内混合)</td><td>&#10004;</td><td>Alpha 混合</td></tr>
  <tr><td><code>glReadPixels</code></td><td>—</td><td><b>&#10004; (1920×1200)</b></td><td>帧共享核心</td></tr>
  <tr><td><code>glDrawArrays (TRIANGLE_STRIP)</code></td><td>&#10004;</td><td>&#10004;</td><td>绘制四边形</td></tr>
</table>


<!-- ═══ 第8章 ═══ -->
<h1>八、GLSL Shader 源码</h1>

<h2>8.1 补盲 — 顶点着色器（带象限裁切）</h2>
<pre><span class="kw">uniform</span> <span class="type">mat4</span> uMVPMatrix;
<span class="kw">uniform</span> <span class="type">mat4</span> uSTMatrix;
<span class="kw">uniform</span> <span class="type">vec4</span> uCropRect;  <span class="cmt">// left, top, right, bottom (0~1)</span>
<span class="kw">attribute</span> <span class="type">vec4</span> aPosition;
<span class="kw">attribute</span> <span class="type">vec4</span> aTexCoord;
<span class="kw">varying</span> <span class="type">vec2</span> vTexCoord;
<span class="kw">void</span> <span class="fn">main</span>() {{
    gl_Position = uMVPMatrix * aPosition;
    <span class="cmt">// 先应用相机变换（含 Y 翻转），再在变换后的坐标空间中裁切</span>
    <span class="type">vec2</span> stCoord = (uSTMatrix * <span class="type">vec4</span>(aTexCoord.xy, <span class="num">0.0</span>, <span class="num">1.0</span>)).xy;
    vTexCoord = <span class="fn">mix</span>(uCropRect.xy, uCropRect.zw, stCoord);
}}</pre>

<div class="info-box">
  <b>关键机制：</b><code>mix(uCropRect.xy, uCropRect.zw, stCoord)</code> 将 (0,0)→(1,1) 纹理坐标映射到裁切区域。例如左盲区 <code>(0, 0.5, 0.5, 1)</code> 时，坐标被线性映射到左下象限。无需 CPU 计算即可实时切换。
</div>

<h2>8.2 补盲 — 片段着色器</h2>
<pre><span class="kw">#extension</span> GL_OES_EGL_image_external : <span class="kw">require</span>
<span class="kw">precision</span> <span class="type">mediump</span> <span class="type">float</span>;
<span class="kw">varying</span> <span class="type">vec2</span> vTexCoord;
<span class="kw">uniform</span> <span class="type">samplerExternalOES</span> sTexture;
<span class="kw">void</span> <span class="fn">main</span>() {{
    gl_FragColor = <span class="fn">texture2D</span>(sTexture, vTexCoord);
}}</pre>

<h2>8.3 EVCam — 片段着色器（带水印）</h2>
<pre><span class="kw">#extension</span> GL_OES_EGL_image_external : <span class="kw">require</span>
<span class="kw">precision</span> <span class="type">mediump</span> <span class="type">float</span>;
<span class="kw">varying</span> <span class="type">vec2</span> vTextureCoord;
<span class="kw">uniform</span> <span class="type">samplerExternalOES</span> sTexture;
<span class="kw">uniform</span> <span class="type">sampler2D</span> sWatermarkTexture;
<span class="kw">uniform</span> <span class="type">vec4</span> uWatermarkRect;  <span class="cmt">// x, y, width, height</span>
<span class="kw">void</span> <span class="fn">main</span>() {{
    <span class="type">vec4</span> videoColor = <span class="fn">texture2D</span>(sTexture, vTextureCoord);
    <span class="kw">if</span> (vTextureCoord.x >= uWatermarkRect.x &&
        vTextureCoord.x <= uWatermarkRect.x + uWatermarkRect.z &&
        vTextureCoord.y >= uWatermarkRect.y &&
        vTextureCoord.y <= uWatermarkRect.y + uWatermarkRect.w) {{
        <span class="type">vec2</span> wmCoord = <span class="type">vec2</span>(
            (vTextureCoord.x - uWatermarkRect.x) / uWatermarkRect.z,
            (vTextureCoord.y - uWatermarkRect.y) / uWatermarkRect.w);
        <span class="type">vec4</span> wmColor = <span class="fn">texture2D</span>(sWatermarkTexture, wmCoord);
        gl_FragColor = <span class="fn">mix</span>(videoColor, wmColor, wmColor.a);
    }} <span class="kw">else</span> {{
        gl_FragColor = videoColor;
    }}
}}</pre>

<h2>8.4 水印实现差异</h2>
<table>
  <tr><th>对比项</th><th>EVCam (EglSurfaceEncoder)</th><th>补盲 (CameraGLRenderer)</th></tr>
  <tr><td>方式</td><td>同一 fragment shader 内 <code>if</code> 判断 + <code>mix()</code></td><td>独立 shader program + <code>glEnable(GL_BLEND)</code></td></tr>
  <tr><td>Pass 数</td><td>单 pass</td><td>双 pass（相机帧 + 水印叠加）</td></tr>
  <tr><td>灵活性</td><td>水印位置固定</td><td>水印独立渲染，可任意调整</td></tr>
</table>


<!-- ═══ 第9章 ═══ -->
<h1>九、性能对比与优化总结</h1>

<h2>9.1 新旧方案性能对比</h2>
<div class="diagram">{imgs['fig06_performance.png']}</div>

<h2>9.2 优化措施详细对照</h2>
<table>
  <tr><th>优化点</th><th>旧方案 (getBitmap)</th><th>新方案 (GL Pipeline)</th><th>提升</th></tr>
  <tr><td><b>相机帧获取</b></td><td><code>TextureView.getBitmap()</code> ~20ms</td><td><code>updateTexImage()</code> ~0.1ms</td><td><span class="highlight">200x</span></td></tr>
  <tr><td><b>象限裁切</b></td><td><code>Canvas.drawBitmap(cropRect)</code> ~5ms</td><td><code>glUniform4f(uCropRect)</code> ~0.1ms</td><td><span class="highlight">50x</span></td></tr>
  <tr><td><b>录制帧输入</b></td><td>Bitmap → MediaRecorder (CPU 拷贝)</td><td><code>eglSwapBuffers</code> → InputSurface</td><td><span class="highlight">零拷贝</span></td></tr>
  <tr><td><b>水印叠加</b></td><td>Canvas 合成 ~3ms</td><td>GL alpha blend ~0.5ms</td><td>6x</td></tr>
  <tr><td><b>帧共享</b></td><td>getBitmap → JPEG → file</td><td>glReadPixels → AtomicRef → SharedMem</td><td>无阻塞</td></tr>
  <tr><td><b>预览切换</b></td><td>重新裁切 Bitmap</td><td>修改 1 个 uniform 值</td><td><span class="highlight">~0ms</span></td></tr>
</table>


<!-- ═══ 第10章 ═══ -->
<h1>十、线程模型</h1>

<h2>10.1 补盲服务线程</h2>
<div class="diagram">{imgs['fig07_threads.png']}</div>

<h2>10.2 EVCam 线程</h2>
<table>
  <tr><th>线程</th><th>优先级</th><th>职责</th></tr>
  <tr><td>主线程</td><td>默认</td><td>UI、按钮事件、TextureView 回调</td></tr>
  <tr><td>Camera HandlerThread ×4</td><td>默认</td><td>每路摄像头 Camera2 操作</td></tr>
  <tr><td>Codec 渲染线程</td><td>DISPLAY</td><td><code>EglSurfaceEncoder.drawFrame()</code></td></tr>
  <tr><td>Encoder Drain 线程</td><td>默认</td><td><code>MediaCodec</code> 输出处理</td></tr>
  <tr><td>帧轮询线程</td><td>默认</td><td>33ms 轮询 JPEG → Canvas 渲染</td></tr>
</table>


<!-- ═══ 第11章 ═══ -->
<h1>十一、IPC 通信机制</h1>

<h2>11.1 通信通道总览</h2>
<div class="diagram">{imgs['fig08_ipc.png']}</div>

<h2>11.2 AIDL 接口</h2>

<h3>IVirtualCamera.aidl（补盲暴露）</h3>
<table>
  <tr><th>方法</th><th>返回类型</th><th>用途</th></tr>
  <tr><td><code>getFrameBuffer()</code></td><td>SharedMemory</td><td>获取帧共享内存句柄</td></tr>
  <tr><td><code>getFrameWidth() / getFrameHeight()</code></td><td>int</td><td>帧尺寸 (1920×1200)</td></tr>
  <tr><td><code>registerFrameCallback(IFrameCallback)</code></td><td>void</td><td>注册新帧通知</td></tr>
  <tr><td><code>startRecording(saveDir, timestamp, segmentMs)</code></td><td>boolean</td><td>GL 管线录制</td></tr>
  <tr><td><code>stopRecording()</code></td><td>void</td><td>停止录制</td></tr>
  <tr><td><code>getGear() / getSpeedKmh()</code></td><td>int</td><td>车辆数据</td></tr>
  <tr><td><code>setRecordingParams(w, h, fps, bitrate)</code></td><td>void</td><td>录制参数</td></tr>
  <tr><td><code>setWatermarkEnabled(boolean)</code></td><td>void</td><td>水印开关</td></tr>
</table>

<h3>IBlindspotCamera.aidl（EVCam 期望）</h3>
<table>
  <tr><th>方法</th><th>用途</th></tr>
  <tr><td><code>registerSurface(Surface)</code></td><td>注册接收画面的 Surface</td></tr>
  <tr><td><code>startRecording(filePath)</code></td><td>开始录制</td></tr>
  <tr><td><code>getCameraWidth() / getCameraHeight()</code></td><td>获取相机尺寸</td></tr>
</table>

<div class="warn-box">
  <b>已知问题：</b>AIDL 接口不匹配 — 补盲返回 <code>IVirtualCamera.Stub</code>，EVCam 期望 <code>IBlindspotCamera.Stub</code>。当前通过 JPEG 文件共享 + 广播控制作为 fallback 方案正常工作。
</div>

<br><br>
<hr style="border: none; border-top: 3px solid #0f3460; margin: 40px 0;">
<p style="text-align: center; color: #999; font-size: 10pt;">
  本文档由代码分析自动生成 &middot; 2026-02-10 &middot; EVCam + 补盲系统技术方案 v2.0
</p>

</body>
</html>'''

with open(OUT, 'w', encoding='utf-8') as f:
    f.write(html)

print(f"\nDocument saved to: {OUT}")
fsize = os.path.getsize(OUT) / 1024 / 1024
print(f"File size: {fsize:.1f} MB")
