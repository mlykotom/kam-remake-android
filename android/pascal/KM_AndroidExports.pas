unit KM_AndroidExports;
{$I KaM_Remake.inc}
// C API of the engine for the Android host (libkamhost.so, JNI glue).
// All calls must come from the GL thread, except the KMAndroidHost state setters.
// Touch input is translated here into the mouse events the original game UI expects:
//  - tap on UI or map = left click
//  - one finger drag on UI = left button drag (sliders, scrollbars, minimap)
//  - one finger drag on map = scroll the map (or left button drag while a road/field/editor tool is active;
//    when placing a building in gameplay, dragging the footprint moves the preview while dragging elsewhere pans)
//  - double tap on map (or long press on map / right-clickable UI) = right button press; keep holding and move to pick troop direction, release to finish
//  - two finger tap = right click
//  - pinch = zoom, two finger drag = scroll
interface

type
  TKMHostCallbacks = record
    ShowMessage: procedure(aTitle, aText: PAnsiChar); cdecl;
    ClipboardSet: procedure(aText: PAnsiChar); cdecl;
    ClipboardGet: function: PAnsiChar; cdecl; // Returned string is owned by the host and valid until next call
    OpenURL: procedure(aURL: PAnsiChar); cdecl;
    Quit: procedure; cdecl;
    PlayVideo: procedure(aPath: PAnsiChar; aVolume, aStretch: Integer); cdecl;
    StopVideo: procedure; cdecl;
    Haptic: procedure; cdecl;
    SetUIScale: procedure(aScale: Single); cdecl;
  end;
  PKMHostCallbacks = ^TKMHostCallbacks;

const
  TOUCH_DOWN = 0;
  TOUCH_MOVE = 1;
  TOUCH_UP = 2;
  TOUCH_CANCEL = 3;
  TOUCH_POINTER_DOWN = 5;
  TOUCH_POINTER_UP = 6;

  KEY_DOWN = 0;
  KEY_UP = 1;

procedure kam_set_callbacks(aCallbacks: PKMHostCallbacks); cdecl;
function kam_init(aDataDir: PAnsiChar; aWidth, aHeight: Integer): Integer; cdecl;
procedure kam_frame; cdecl;
procedure kam_resize(aWidth, aHeight: Integer); cdecl;
procedure kam_set_safe_insets(aLeft, aRight: Integer); cdecl;
procedure kam_set_ui_scale(aScale: Single); cdecl;
procedure kam_pause; cdecl;
procedure kam_resume; cdecl;
procedure kam_shutdown; cdecl;
procedure kam_touch(aAction, aPointerCount: Integer; X0, Y0, X1, Y1: Single); cdecl;
procedure kam_mouse(aAction, aButton, X, Y: Integer); cdecl;
procedure kam_wheel(aSteps, X, Y: Integer); cdecl;
procedure kam_key(aAction, aVirtualKey, aShift: Integer); cdecl;
procedure kam_char(aUnicodeChar: Integer); cdecl;
procedure kam_back; cdecl;
function kam_text_input_active: Integer; cdecl;
procedure kam_video_finished; cdecl;
function kam_in_game: Integer; cdecl;
function kam_toolbar_width: Integer; cdecl;
function kam_text_input_bottom: Integer; cdecl;
function kam_text_field_tapped: Integer; cdecl;
procedure kam_selftest(aMode: PAnsiChar); cdecl;

implementation
uses
  Classes, SysUtils, Math, Types, Controls, LCLType, BaseUnix,
  KM_Main, KM_GameApp, KM_Defaults, KM_Points, KM_Cursor, KM_System, KM_HandSpectator, KM_HandsCollection,

  KM_InterfaceDefaults, KM_InterfaceGame, KM_InterfaceGamePlay, KM_InterfaceMapEditor, KM_Game, KM_Viewport,
  KM_Controls, KM_ControlsBase, KM_ControlsEdit, KM_ControlsList, KM_ControlsMemo, KM_ControlsScroll, KM_ControlsTrackBar,


  KM_Log, dglOpenGL, KM_ResSprites, KM_ResTypes, KM_Video, KM_Render,
  KM_AndroidSelfTest,
  KMAndroidHost, KMAndroidLog;

const
  TAP_SLOP = 14;          // px (render resolution) finger can move and still be a tap
  DOUBLE_TAP_SLOP = 36;   // px (render resolution) max distance between the two taps of a double-tap
  PINCH_SLOP = 32;        // px (render resolution) two-finger movement before becoming a zoom/pan instead of a tap
  LONG_PRESS_MS = 250;    // hold time to become a right button press
  PEEK_HINT_MS = 300;     // stationary hold time on UI control to show floating tooltip without clicking
  DOUBLE_TAP_MS = 250;    // max time from first tap release to second touch down
  TWO_FINGER_TAP_MS = 300;

type
  TKMTouchState = (tsIdle, tsUIPending, tsUIDrag, tsMapPending, tsMapPan, tsMapDragLeft, tsRightHold, tsHintHold, tsPinch, tsConsumed,
                   tsSidebarPending, tsSidebarScroll, tsHousePreviewPending, tsHousePreviewDrag);

var
  gCallbacks: TKMHostCallbacks;
  fTouchState: TKMTouchState = tsIdle;
  fDownPos, fLastPos, fSecondDownPos: TPointF;
  fDownTime: QWord;
  fRightHoldMoved: Boolean;
  fLastMapTap: Boolean;
  fLastMapTapPos: TPointF;
  fLastMapTapTime: QWord;
  fButtons: TShiftState = [];
  // Pinch
  fPinchStartDist, fPinchStartZoom: Single;
  fPinchLastMid: TPointF;
  fPinchStartTime: QWord;
  fPinchMoved: Boolean;
  fClipboardText: UnicodeString;
  fTextFieldTapped: Boolean;


function MakePointF(aX, aY: Single): TPointF;
begin
  Result.X := aX;
  Result.Y := aY;
end;


function CellPx(aViewport: TKMViewport): Single;
begin
  Result := CELL_SIZE_PX * aViewport.Zoom;
end;


procedure HostShowMessage(const aTitle, aText: UnicodeString);
begin
  if Assigned(gCallbacks.ShowMessage) then
    gCallbacks.ShowMessage(PAnsiChar(UTF8Encode(aTitle)), PAnsiChar(UTF8Encode(aText)));
end;

procedure HostClipboardSet(const aText: UnicodeString);
begin
  if Assigned(gCallbacks.ClipboardSet) then
    gCallbacks.ClipboardSet(PAnsiChar(UTF8Encode(aText)));
end;

function HostClipboardGet: UnicodeString;
begin
  Result := '';
  if Assigned(gCallbacks.ClipboardGet) then
    Result := UTF8Decode(gCallbacks.ClipboardGet());
end;

procedure HostOpenURL(const aURL: UnicodeString);
begin
  if Assigned(gCallbacks.OpenURL) then
    gCallbacks.OpenURL(PAnsiChar(UTF8Encode(aURL)));
end;

procedure HostPlayVideo(const aPath: UnicodeString; aVolume: Integer; aStretch: Boolean);
begin
  if Assigned(gCallbacks.PlayVideo) then
    gCallbacks.PlayVideo(PAnsiChar(UTF8Encode(aPath)), aVolume, Ord(aStretch));
end;

procedure HostStopVideo;
begin
  if Assigned(gCallbacks.StopVideo) then
    gCallbacks.StopVideo();
end;

procedure HostHaptic;
begin
  if Assigned(gCallbacks.Haptic) then
    gCallbacks.Haptic();
end;

procedure HostSetUIScale(aScale: Single);
begin
  if Assigned(gCallbacks.SetUIScale) then
    gCallbacks.SetUIScale(aScale);
end;

procedure HostQuit;
begin
  if Assigned(gCallbacks.Quit) then
    gCallbacks.Quit();
end;


procedure kam_set_callbacks(aCallbacks: PKMHostCallbacks); cdecl;
begin
  gCallbacks := aCallbacks^;
  gHostShowMessage := HostShowMessage;
  gHostClipboardSet := HostClipboardSet;
  gHostClipboardGet := HostClipboardGet;
  gHostOpenURL := HostOpenURL;
  gHostQuit := HostQuit;
  gHostPlayVideo := HostPlayVideo;
  gHostStopVideo := HostStopVideo;
  gHostHaptic := HostHaptic;
  gHostSetUIScale := HostSetUIScale;
end;


{$IFDEF ANDROID_GLDEBUG}
var
  fOrigPopMatrix: TglPopMatrix;
  fOrigPopAttrib: TglPopAttrib;

// Debug: crash with a stack trace on GL stack underflow
procedure DbgCheckUnderflow(const aWhat: string);
begin
  if glGetError = GL_STACK_UNDERFLOW then
  begin
    ALog('GL stack underflow in ' + aWhat + ', crashing for stack trace');
    PInteger(nil)^ := 0;
  end;
end;

procedure DbgPopMatrix; cdecl;
begin
  glGetError;
  fOrigPopMatrix();
  DbgCheckUnderflow('glPopMatrix');
end;

procedure DbgPopAttrib; cdecl;
begin
  glGetError;
  fOrigPopAttrib();
  DbgCheckUnderflow('glPopAttrib');
end;

procedure InstallGLDebugHooks;
begin
  if not FileExists(ExeDir + 'glhooks.txt') then Exit;
  fOrigPopMatrix := glPopMatrix;
  fOrigPopAttrib := glPopAttrib;
  glPopMatrix := DbgPopMatrix;
  glPopAttrib := DbgPopAttrib;
  ALog('GL debug hooks installed');
end;

{$ENDIF}

{$IFDEF ANDROID_MEMDEBUG}
var
  fOldMM: TMemoryManager;

procedure LogBigAlloc(aSize: PtrUInt);
var
  bp, addr: Pointer;
  I: Integer;
  s: string;
begin
  s := Format('HUGE ALLOC %d MB, kam_frame=%p stack:', [aSize shr 20, Pointer(@kam_frame)]);
  bp := get_frame;
  for I := 0 to 25 do
  begin
    if bp = nil then Break;
    addr := get_caller_addr(bp);
    s := s + ' ' + HexStr(addr);
    bp := get_caller_frame(bp);
  end;
  ALog(s);
end;

function DbgGetMem(aSize: PtrUInt): Pointer;
begin
  if aSize > 512 shl 20 then
  begin
    LogBigAlloc(aSize);
    Exit(nil);
  end;
  Result := fOldMM.GetMem(aSize);
end;

function DbgReAllocMem(var p: Pointer; aSize: PtrUInt): Pointer;
begin
  if aSize > 512 shl 20 then
  begin
    LogBigAlloc(aSize);
    Exit(nil);
  end;
  Result := fOldMM.ReAllocMem(p, aSize);
end;

procedure InstallMemDebug;
var
  mm: TMemoryManager;
begin
  GetMemoryManager(fOldMM);
  mm := fOldMM;
  mm.GetMem := DbgGetMem;
  mm.ReAllocMem := DbgReAllocMem;
  SetMemoryManager(mm);
end;
{$ENDIF}

function kam_init(aDataDir: PAnsiChar; aWidth, aHeight: Integer): Integer; cdecl;
begin
  {$IFDEF ANDROID_MEMDEBUG}
  InstallMemDebug;
  {$ENDIF}
  Result := 0;
  try
    // Engine threads use TThread.Synchronize/Queue, which run on MainThreadID when it calls CheckSynchronize.
    // The library was loaded on the Java UI thread, but the engine lives on the GL thread
    MainThreadID := GetCurrentThreadId;
    // Network code must not be killed by writes to closed sockets
    fpSignal(SIGPIPE, SignalHandler(SIG_IGN));
    // Touch screen has no hovering cursor: scrolling the map by holding the cursor at the screen edge
    // would trigger from the last touch point. It is re-enabled when a physical mouse is used
    IGNORE_MOUSE_SCROLLING := True;
    ExeDir := IncludeTrailingPathDelimiter(UTF8Decode(aDataDir));
    gHostScreenWidth := aWidth;
    gHostScreenHeight := aHeight;
    ALog(Format('kam_init: %s %dx%d', [string(ExeDir), aWidth, aHeight]));

    gMain := TKMMain.Create;
    {$IFDEF ANDROID_GLDEBUG}
    gOnGLInitialized := InstallGLDebugHooks;
    {$ENDIF}
    if gMain.Start(aWidth, aHeight) then
      Result := 1;
  except
    on E: Exception do
    begin
      ALog('kam_init failed: ' + E.ClassName + ': ' + E.Message);
      HostShowMessage('KaM Remake', UnicodeString('Error starting the game:' + sLineBreak + E.Message));
    end;
  end;
end;


procedure CheckLongPress; forward;
procedure UpdateScrollMomentum; forward;


var
  fLastGLErrorLog: QWord;

{$IFDEF ANDROID_GLDEBUG}
var
  fLastGLStateLog: QWord;

procedure LogGLState;
const
  CAPS: array [0..11] of GLenum = (GL_DEPTH_TEST, GL_STENCIL_TEST, GL_ALPHA_TEST, GL_BLEND, GL_SCISSOR_TEST, GL_TEXTURE_2D, GL_CULL_FACE, GL_LIGHTING,
                                   GL_CLIP_PLANE0, GL_CLIP_PLANE1, GL_CLIP_PLANE2, GL_CLIP_PLANE3);
  NAMES: array [0..11] of string = ('depth', 'stencil', 'alpha', 'blend', 'scissor', 'tex2d', 'cull', 'light', 'clip0', 'clip1', 'clip2', 'clip3');
var
  I: Integer;
  s: string;
  v: array [0..15] of GLint;
  f: array [0..15] of GLfloat;
begin
  if GetTickCount64 - fLastGLStateLog < 5000 then Exit;
  fLastGLStateLog := GetTickCount64;
  s := '';
  for I := 0 to High(CAPS) do
    if glIsEnabled(CAPS[I]) then s := s + NAMES[I] + ' ';
  glGetIntegerv(GL_BLEND_SRC, @v[0]); glGetIntegerv(GL_BLEND_DST, @v[1]);
  s := s + Format('blend=%x/%x ', [v[0], v[1]]);
  glGetIntegerv(GL_MATRIX_MODE, @v[0]); s := s + Format('mm=%x ', [v[0]]);
  glGetIntegerv(GL_MODELVIEW_STACK_DEPTH, @v[0]); glGetIntegerv(GL_PROJECTION_STACK_DEPTH, @v[1]);
  s := s + Format('stack=%d/%d ', [v[0], v[1]]);
  glGetIntegerv(GL_VIEWPORT, @v[0]); s := s + Format('vp=%d,%d,%d,%d ', [v[0], v[1], v[2], v[3]]);
  glGetFloatv(GL_CURRENT_COLOR, @f[0]); s := s + Format('col=%.2f,%.2f,%.2f,%.2f ', [f[0], f[1], f[2], f[3]]);
  glGetFloatv(GL_PROJECTION_MATRIX, @f[0]); s := s + Format('proj=%.4f,%.4f,%.4f,%.4f ', [f[0], f[5], f[10], f[14]]);
  glGetIntegerv(GL_TEXTURE_BINDING_2D, @v[0]); s := s + Format('tex=%d ', [v[0]]);
  glGetIntegerv(GL_ACTIVE_TEXTURE, @v[0]); glGetIntegerv(GL_CLIENT_ACTIVE_TEXTURE, @v[1]);
  s := s + Format('active=%x client=%x ', [v[0], v[1]]);
  glGetIntegerv(GL_COLOR_WRITEMASK, @v[0]); s := s + Format('mask=%d%d%d%d ', [v[0], v[1], v[2], v[3]]);
  glGetIntegerv(GL_ARRAY_BUFFER_BINDING, @v[0]); s := s + Format('vbo=%d ', [v[0]]);
  glGetTexEnviv(GL_TEXTURE_ENV, GL_TEXTURE_ENV_MODE, @v[0]); s := s + Format('env=%x ', [v[0]]);
  for I := 0 to 3 do
    if glIsEnabled(GL_TEXTURE_COORD_ARRAY) then s := s + 'tcarray ';
  v[0] := 0; v[1] := 0;
  for I := 0 to High(gGFXData[rxGUI]) do
  begin
    if gGFXData[rxGUI, I].Tex.TexID <> 0 then Inc(v[0]);
    if gGFXData[rxGUI, I].PxWidth <> 0 then Inc(v[1]);
  end;
  s := s + Format('gui=%d tex=%d px=%d ', [Length(gGFXData[rxGUI]), v[0], v[1]]);
  if Length(gGFXData[rxGUI]) > 3 then
    with gGFXData[rxGUI, 3] do
      s := s + Format('gui3: tex=%d isTex=%d uv=%.3f,%.3f-%.3f,%.3f px=%dx%d', [Tex.TexID, Ord(glIsTexture(Tex.TexID)), Tex.u1, Tex.v1, Tex.u2, Tex.v2, PxWidth, PxHeight]);
  ALog('GL state: ' + s);
  if gMain <> nil then ALog('FPS: ' + gMain.FPSString);
end;

// Debug aid: report GL errors (at most once per 2 seconds)
var
  fLastDbgRead: QWord;

procedure ReadDbgMask;
var
  sl: TStringList;
begin
  if GetTickCount64 - fLastDbgRead < 2000 then Exit;
  fLastDbgRead := GetTickCount64;
  if not FileExists(ExeDir + 'dbg.txt') then
  begin
    gAndroidDbgMask := 0;
    Exit;
  end;
  sl := TStringList.Create;
  try
    sl.LoadFromFile(ExeDir + 'dbg.txt');
    gAndroidDbgMask := StrToIntDef(Trim(sl.Text), 0);
  finally
    sl.Free;
  end;
end;

// Debug: draw the GUI atlas in a corner to check textured rendering state
procedure DrawTestQuad;
begin
  if gAndroidDbgMask and 64 = 0 then Exit;
  TKMRender.BindTexture(1);
  glColor4f(1, 1, 1, 1);
  glBegin(GL_QUADS);
    glTexCoord2f(0, 0); glVertex2f(300, 600);
    glTexCoord2f(1, 0); glVertex2f(800, 600);
    glTexCoord2f(1, 1); glVertex2f(800, 1100);
    glTexCoord2f(0, 1); glVertex2f(300, 1100);
  glEnd;
  TKMRender.BindTexture(0);
end;

procedure LogGLErrors;
var
  err: GLenum;
  s: string;
begin
  DrawTestQuad;
  ReadDbgMask;
  if GetTickCount64 - fLastGLErrorLog < 2000 then Exit;
  s := '';
  err := glGetError;
  while (err <> GL_NO_ERROR) and (Length(s) < 200) do
  begin
    s := s + IntToHex(err, 4) + ' ';
    err := glGetError;
  end;
  if s <> '' then
  begin
    fLastGLErrorLog := GetTickCount64;
    ALog('GL errors: ' + s);
  end;
  LogGLState;
end;

{$ENDIF}

procedure kam_frame; cdecl;
begin
  if (gMain = nil) or not gMain.Started then Exit;
  try
    UpdateScrollMomentum;
    CheckSynchronize;

    CheckLongPress;
    if SelfTestActive then
      SelfTestFrame;
    gMain.Frame;
    {$IFDEF ANDROID_GLDEBUG}
    LogGLErrors;
    {$ENDIF}
  except
    on E: Exception do
    begin
      ALog('Exception in frame: ' + E.ClassName + ': ' + E.Message);
      if gLog <> nil then
        gLog.AddTime('Exception: ' + E.Message);
      HostShowMessage('KaM Remake', UnicodeString('Error: ' + E.Message));
    end;
  end;
end;


procedure kam_resize(aWidth, aHeight: Integer); cdecl;
begin
  gHostScreenWidth := aWidth;
  gHostScreenHeight := aHeight;
  if gMain <> nil then
    gMain.SurfaceResized(aWidth, aHeight);
end;


procedure kam_set_safe_insets(aLeft, aRight: Integer); cdecl;
begin
  aLeft := Max(0, aLeft);
  aRight := Max(0, aRight);
  if (gHostSafeInsetLeft = aLeft) and (gHostSafeInsetRight = aRight) then Exit;
  gHostSafeInsetLeft := aLeft;
  gHostSafeInsetRight := aRight;
  ALog(Format('kam_set_safe_insets: left=%d right=%d', [aLeft, aRight]));
  if (gMain <> nil) and gMain.Started then
    gMain.SurfaceResized(gHostScreenWidth, gHostScreenHeight);
end;


procedure kam_set_ui_scale(aScale: Single); cdecl;
begin
  gHostUIScale := EnsureRange(aScale, 0.5, 2.0);
end;


procedure kam_pause; cdecl;
begin
  if gMain <> nil then
    gMain.AppPause;
end;


procedure kam_resume; cdecl;
begin
  if gMain <> nil then
    gMain.AppResume;
end;


procedure kam_shutdown; cdecl;
begin
  if (gMain <> nil) and gMain.Started then
    gMain.Stop(nil);
end;


{ Mouse emulation }

function MousePt(const P: TPointF): TPoint;
begin
  Result := Point(Round(P.X), Round(P.Y));
end;


procedure SendMove(const P: TPointF);
var
  pt: TPoint;
begin
  if gGameApp = nil then Exit;
  pt := MousePt(P);
  gHostCursorPos := pt;
  gGameApp.MouseMove(fButtons, pt.X, pt.Y);
end;


procedure SendDown(aButton: TMouseButton; const P: TPointF);
var
  pt: TPoint;
begin
  if gGameApp = nil then Exit;
  pt := MousePt(P);
  gHostCursorPos := pt;
  case aButton of
    mbLeft:   begin Include(fButtons, ssLeft);   gHostKeyDown[VK_LBUTTON] := True; end;
    mbRight:  begin Include(fButtons, ssRight);  gHostKeyDown[VK_RBUTTON] := True; end;
    mbMiddle: begin Include(fButtons, ssMiddle); gHostKeyDown[VK_MBUTTON] := True; end;
  end;
  // Same as desktop TFormMain: middle button is handled as a key (drag scrolling)
  if aButton = mbMiddle then
    gGameApp.KeyDown(VK_MBUTTON, fButtons, True)
  else
    gGameApp.MouseDown(aButton, fButtons, pt.X, pt.Y);
end;


procedure SendUp(aButton: TMouseButton; const P: TPointF);
var
  pt: TPoint;
  shift: TShiftState;
begin
  if gGameApp = nil then Exit;
  pt := MousePt(P);
  gHostCursorPos := pt;
  // Same as desktop TFormMain: the released button is included in Shift for MouseUp
  // (e.g. message scrolls check ssLeft to tell open from delete)
  shift := fButtons;
  case aButton of
    mbLeft:   begin Include(shift, ssLeft);   Exclude(fButtons, ssLeft);   gHostKeyDown[VK_LBUTTON] := False; end;
    mbRight:  begin Include(shift, ssRight);  Exclude(fButtons, ssRight);  gHostKeyDown[VK_RBUTTON] := False; end;
    mbMiddle: begin Include(shift, ssMiddle); Exclude(fButtons, ssMiddle); gHostKeyDown[VK_MBUTTON] := False; end;
  end;
  if aButton = mbMiddle then
    gGameApp.KeyUp(VK_MBUTTON, shift)
  else
    gGameApp.MouseUp(aButton, shift, pt.X, pt.Y);
end;


function ActiveControls: TKMMasterControl;
begin
  Result := nil;
  if (gGameApp <> nil) and (gGameApp.ActiveInterface <> nil) then
    Result := gGameApp.ActiveInterface.MyControls;
end;


// Interface (gameplay or map editor) whose left toolbar can be scrolled under the point
function ScrollableSidebarAt(const P: TPointF): TObject;
begin
  Result := nil;
  if (gGame = nil) or (gGameApp = nil) then Exit;
  if (gGame.GamePlayInterface <> nil) and (gGameApp.ActiveInterface = gGame.GamePlayInterface)
  and gGame.GamePlayInterface.IsOverScrollableSidebar(Round(P.X), Round(P.Y)) then
    Result := gGame.GamePlayInterface
  else
  if (gGame.MapEditorInterface <> nil) and (gGameApp.ActiveInterface = gGame.MapEditorInterface)
  and gGame.MapEditorInterface.IsOverScrollableSidebar(Round(P.X), Round(P.Y)) then
    Result := gGame.MapEditorInterface;
end;


procedure ScrollSidebarOf(aInterface: TObject; aDelta: Integer);
begin
  if aInterface is TKMGamePlayInterface then
    TKMGamePlayInterface(aInterface).ScrollSidebar(aDelta)
  else
  if aInterface is TKMMapEdInterface then
    TKMMapEdInterface(aInterface).ScrollSidebar(aDelta);
end;


var
  fScrollTarget: TObject;
  fScrollInterface: TObject;
  fScrollAccumPx: Single;
  fScrollVelPxPerSec: Single;
  fLastScrollMoveTime: QWord;
  fMomentumActive: Boolean;
  fLastMomentumTime: QWord;
  fLastUITap: Boolean;
  fLastUITapPos: TPointF;
  fLastUITapTime: QWord;


// Scrollable list/memo/panel or left sidebar under the point (inner scrollable control takes priority)
function ScrollableTargetAt(const P: TPointF): TObject;
var
  ctrls: TKMMasterControl;
  pt: TPoint;
  ctrl, walk: TKMControl;
begin
  Result := nil;
  ctrls := ActiveControls;
  if ctrls <> nil then
  begin
    pt := MousePt(P);
    ctrl := ctrls.HitControl(pt.X, pt.Y);
    // Let direct scrollbar thumb/button and horizontal slider drags use standard UI dragging
    if (ctrl <> nil)
    and ((ctrl is TKMScrollBar) or (ctrl is TKMTrackBar) or ((ctrl.Parent <> nil) and (ctrl.Parent is TKMScrollBar))) then
      Exit(nil);

    walk := ctrl;
    while walk <> nil do
    begin
      if (walk is TKMListBox) and (TKMListBox(walk).RowCount > TKMListBox(walk).GetVisibleRows) then
        Exit(walk);
      if (walk is TKMColumnBox) and (TKMColumnBox(walk).RowCount > TKMColumnBox(walk).GetVisibleRows) then
        Exit(walk);
      if (walk is TKMMemo) and TKMMemo(walk).CanScroll then
        Exit(walk);
      if (walk is TKMScrollPanel)
      and TKMScrollPanel(walk).ScrollV.Visible
      and (TKMScrollPanel(walk).ScrollV.MaxValue > TKMScrollPanel(walk).ScrollV.MinValue) then
        Exit(walk);
      walk := walk.Parent;
    end;
  end;

  Result := ScrollableSidebarAt(P);
end;


function IsControlLiveInPanel(aPanel: TKMPanel; aTarget: TKMControl): Boolean;
var
  I: Integer;
  child: TKMControl;
begin
  Result := False;
  if (aPanel = nil) or not aPanel.IsSetVisible then Exit;
  if aPanel = aTarget then Exit(True);
  for I := 0 to aPanel.ChildCount - 1 do
  begin
    child := aPanel.Childs[I];
    if not child.IsSetVisible then Continue;
    if child = aTarget then Exit(True);
    if (child is TKMPanel) and IsControlLiveInPanel(TKMPanel(child), aTarget) then
      Exit(True);
  end;
end;


function IsScrollTargetValid(aTarget, aInterface: TObject): Boolean;
var
  ctrls: TKMMasterControl;
begin
  Result := False;
  if (aTarget = nil) or (gGameApp = nil) or (gGameApp.ActiveInterface <> aInterface) then Exit;
  if (aTarget is TKMGamePlayInterface) or (aTarget is TKMMapEdInterface) then
    Exit((gGame <> nil) and (gGameApp.ActiveInterface = aTarget));
  if aTarget is TKMControl then
  begin
    ctrls := ActiveControls;
    Result := (ctrls <> nil) and IsControlLiveInPanel(ctrls.MasterPanel, TKMControl(aTarget));
  end;
end;


function ApplyScrollTargetDelta(aTarget, aInterface: TObject; var aAccumPx: Single; aDeltaPx: Single): Boolean;
var
  unitPx, totalPx: Single;
  steps, oldPos, newPos: Integer;
begin
  Result := False;
  if not IsScrollTargetValid(aTarget, aInterface) then Exit;

  totalPx := aAccumPx + aDeltaPx;
  if aTarget is TKMGamePlayInterface then
  begin
    unitPx := Max(1.0, TKMGamePlayInterface(aTarget).GetSidebarRenderScale);
    steps := Trunc(totalPx / unitPx);
    if steps = 0 then begin aAccumPx := totalPx; Exit(True); end;
    aAccumPx := totalPx - steps * unitPx;
    oldPos := TKMGamePlayInterface(aTarget).SidebarScroll;
    TKMGamePlayInterface(aTarget).ScrollSidebar(Round(steps * unitPx));
    Result := TKMGamePlayInterface(aTarget).SidebarScroll <> oldPos;
    Exit;
  end;

  if aTarget is TKMMapEdInterface then
  begin
    unitPx := Max(1.0, TKMMapEdInterface(aTarget).GetSidebarRenderScale);
    steps := Trunc(totalPx / unitPx);
    if steps = 0 then begin aAccumPx := totalPx; Exit(True); end;
    aAccumPx := totalPx - steps * unitPx;
    TKMMapEdInterface(aTarget).ScrollSidebar(Round(steps * unitPx));
    Result := True;
    Exit;
  end;

  if aTarget is TKMListBox then
  begin
    unitPx := Max(1.0, TKMListBox(aTarget).ItemHeight * TKMListBox(aTarget).EffectiveRenderScale);
    steps := Trunc(totalPx / unitPx);
    if steps = 0 then begin aAccumPx := totalPx; Exit(True); end;
    aAccumPx := totalPx - steps * unitPx;
    oldPos := TKMListBox(aTarget).TopIndex;
    newPos := EnsureRange(oldPos + steps, 0, Max(0, TKMListBox(aTarget).RowCount - TKMListBox(aTarget).GetVisibleRows));
    TKMListBox(aTarget).TopIndex := newPos;
    Result := newPos <> oldPos;
    Exit;
  end;

  if aTarget is TKMColumnBox then
  begin
    unitPx := Max(1.0, TKMColumnBox(aTarget).ItemHeight * TKMColumnBox(aTarget).EffectiveRenderScale);
    steps := Trunc(totalPx / unitPx);
    if steps = 0 then begin aAccumPx := totalPx; Exit(True); end;
    aAccumPx := totalPx - steps * unitPx;
    oldPos := TKMColumnBox(aTarget).TopIndex;
    newPos := EnsureRange(oldPos + steps, 0, Max(0, TKMColumnBox(aTarget).RowCount - TKMColumnBox(aTarget).GetVisibleRows));
    TKMColumnBox(aTarget).TopIndex := newPos;
    Result := newPos <> oldPos;
    Exit;
  end;

  if aTarget is TKMMemo then
  begin
    unitPx := Max(1.0, TKMMemo(aTarget).ItemHeight * TKMMemo(aTarget).EffectiveRenderScale);
    steps := Trunc(totalPx / unitPx);
    if steps = 0 then begin aAccumPx := totalPx; Exit(True); end;
    aAccumPx := totalPx - steps * unitPx;
    oldPos := TKMMemo(aTarget).TopIndex;
    TKMMemo(aTarget).TopIndex := EnsureRange(oldPos + steps, Low(SmallInt), High(SmallInt));
    Result := TKMMemo(aTarget).TopIndex <> oldPos;
    Exit;
  end;

  if aTarget is TKMScrollPanel then
  begin
    unitPx := Max(1.0, TKMScrollPanel(aTarget).EffectiveRenderScale);
    steps := Trunc(totalPx / unitPx);
    if steps = 0 then begin aAccumPx := totalPx; Exit(True); end;
    aAccumPx := totalPx - steps * unitPx;
    oldPos := TKMScrollPanel(aTarget).ScrollV.Position;
    newPos := EnsureRange(oldPos + steps, TKMScrollPanel(aTarget).ScrollV.MinValue, TKMScrollPanel(aTarget).ScrollV.MaxValue);
    TKMScrollPanel(aTarget).ScrollV.Position := newPos;
    Result := newPos <> oldPos;
  end;
end;


procedure StopScrollMomentum;
begin
  fMomentumActive := False;
  fScrollVelPxPerSec := 0;
  fScrollAccumPx := 0;
end;


procedure RecordScrollMove(aDeltaY: Single);
var
  nowMs, dtMs: QWord;
  instVel: Single;
begin
  nowMs := GetTickCount64;
  dtMs := nowMs - fLastScrollMoveTime;
  fLastScrollMoveTime := nowMs;
  ApplyScrollTargetDelta(fScrollTarget, fScrollInterface, fScrollAccumPx, aDeltaY);
  if (dtMs > 0) and (dtMs <= 120) then
  begin
    instVel := EnsureRange((aDeltaY * 1000.0) / dtMs, -4500.0, 4500.0);
    if fScrollVelPxPerSec = 0 then
      fScrollVelPxPerSec := instVel
    else
      fScrollVelPxPerSec := fScrollVelPxPerSec * 0.35 + instVel * 0.65;
  end
  else if dtMs > 120 then
    fScrollVelPxPerSec := 0;
end;


procedure StartScrollMomentum;
var
  nowMs: QWord;
begin
  nowMs := GetTickCount64;
  if (nowMs - fLastScrollMoveTime <= 90)
  and (Abs(fScrollVelPxPerSec) >= 90.0)
  and IsScrollTargetValid(fScrollTarget, fScrollInterface) then
  begin
    fMomentumActive := True;
    fLastMomentumTime := nowMs;
  end
  else
    StopScrollMomentum;
end;


procedure UpdateScrollMomentum;
const
  FRICTION_PER_SEC = 4.5;
  MIN_FLING_VEL = 25.0;
var
  nowMs: QWord;
  dtSec: Single;
begin
  if not fMomentumActive then Exit;
  nowMs := GetTickCount64;
  dtSec := EnsureRange((nowMs - fLastMomentumTime) / 1000.0, 0.001, 0.050);
  fLastMomentumTime := nowMs;

  if not ApplyScrollTargetDelta(fScrollTarget, fScrollInterface, fScrollAccumPx, fScrollVelPxPerSec * dtSec) then
  begin
    StopScrollMomentum;
    Exit;
  end;

  fScrollVelPxPerSec := fScrollVelPxPerSec * Exp(-FRICTION_PER_SEC * dtSec);
  if Abs(fScrollVelPxPerSec) < MIN_FLING_VEL then
    StopScrollMomentum;
end;



function GameViewport: TKMViewport;
begin
  Result := nil;
  if (gGameApp <> nil) and (gGameApp.ActiveInterface is TKMUserInterfaceGame) then
    Result := TKMUserInterfaceGame(gGameApp.ActiveInterface).Viewport;
end;


// Is there an UI control under the point (anything that is not the game map)
function IsOverUI(const P: TPointF): Boolean;
var
  ctrls: TKMMasterControl;
  pt: TPoint;
begin
  // Without the game map (menus) everything is UI
  if GameViewport = nil then Exit(True);
  ctrls := ActiveControls;
  if ctrls = nil then Exit(True);
  pt := MousePt(P);
  Result := ((pt.X >= gHostSafeInsetLeft) and (pt.X < GameViewport.ToolbarWidth))
            or (pt.Y >= GameViewport.ViewRect.Bottom)
            or (ctrls.HitControl(pt.X, pt.Y) <> nil);
end;




// Does the UI control under the point respond to right-click (e.g. Market wares, Storehouse/Barracks flags, +/- 10 buttons, minimap)
function IsRightClickableUI(const P: TPointF): Boolean;
var
  ctrls: TKMMasterControl;
  pt: TPoint;
  ctrl: TKMControl;
begin
  Result := False;
  ctrls := ActiveControls;
  if ctrls = nil then Exit;
  pt := MousePt(P);
  ctrl := ctrls.HitControl(pt.X, pt.Y);
  if ctrl <> nil then
    Result := Assigned(ctrl.OnClickShift) or Assigned(ctrl.OnClickRight);
end;


// Is there an interactive UI control (button, list, checkbox, slider, etc.) under the point
function IsActionableUI(const P: TPointF): Boolean;
var
  ctrls: TKMMasterControl;
  pt: TPoint;
  ctrl: TKMControl;
begin
  Result := False;
  ctrls := ActiveControls;
  if ctrls = nil then Exit;
  pt := MousePt(P);
  ctrl := ctrls.HitControl(pt.X, pt.Y);
  if (ctrl = nil) or not ctrl.Enabled then Exit;
  if Assigned(ctrl.OnClick) or Assigned(ctrl.OnClickShift) or Assigned(ctrl.OnClickRight) or Assigned(ctrl.OnDoubleClick) then
    Exit(True);
  if ctrl is TKMButtonFlatCommon then
    Exit(TKMButtonFlatCommon(ctrl).Clickable);
  Result := not ((ctrl is TKMPanel) or (ctrl is TKMBevel) or (ctrl is TKMShape)
                 or (ctrl is TKMLabel) or (ctrl is TKMImage) or (ctrl is TKMImageStack));
end;


function ActiveGamePlay: TKMGamePlayInterface;
begin
  Result := nil;
  if (gGame <> nil) and (gGameApp <> nil) and (gGame.GamePlayInterface <> nil)
     and (gGameApp.ActiveInterface = gGame.GamePlayInterface) then
    Result := gGame.GamePlayInterface;
end;


function TouchHouseModeActive: Boolean;
var
  gp: TKMGamePlayInterface;
begin
  gp := ActiveGamePlay;
  Result := (gp <> nil) and gp.IsTouchHousePlacement;
end;


function GameplayBuildToolActive: Boolean;
begin
  Result := (ActiveGamePlay <> nil) and (gCursor <> nil)
            and (gCursor.Mode in [cmErase, cmRoad, cmField, cmWine, cmHouses]);
end;


// Some cursor modes (roads, fields, map editor brushes) are applied by dragging with the left button
function MapToolActive: Boolean;
begin
  if TouchHouseModeActive then Exit(False);
  Result := (gCursor <> nil) and (gCursor.Mode <> cmNone);
end;


procedure PanViewport(dX, dY: Single);
var
  vp: TKMViewport;
  pos: TKMPointF;
begin
  vp := GameViewport;
  if vp = nil then Exit;
  pos := vp.Position;
  pos.X := pos.X - dX / CellPx(vp);
  pos.Y := pos.Y - dY / CellPx(vp);
  vp.Position := pos;
end;


procedure ZoomViewport(aZoom: Single; const aMid: TPointF);
var
  vp: TKMViewport;
  centerX, centerY: Single;
  mapX, mapY: Single;
  pos: TKMPointF;
begin
  vp := GameViewport;
  if vp = nil then Exit;
  centerX := vp.ViewRect.Left + vp.ViewportClip.X / 2;
  centerY := vp.ViewRect.Top + vp.ViewportClip.Y / 2;
  // Keep the map point under fingers in place
  mapX := vp.Position.X + (aMid.X - centerX) / CellPx(vp);
  mapY := vp.Position.Y + (aMid.Y - centerY) / CellPx(vp);
  vp.Zoom := aZoom;
  pos.X := mapX - (aMid.X - centerX) / CellPx(vp);
  pos.Y := mapY - (aMid.Y - centerY) / CellPx(vp);
  vp.Position := pos;
end;


function Dist(const A, B: TPointF): Single;
begin
  Result := Hypot(A.X - B.X, A.Y - B.Y);
end;


procedure ReleaseButtons(const P: TPointF);
begin
  if ssLeft in fButtons then SendUp(mbLeft, P);
  if ssRight in fButtons then SendUp(mbRight, P);
end;


procedure PerformLeftTap(const P: TPointF);
var
  isAction: Boolean;
  prevSelected: TObject;
  tapPt: TPointF;
begin
  tapPt := P;
  if IsOverUI(P) then
  begin
    if fLastUITap and (GetTickCount64 - fLastUITapTime <= 350) and (Dist(P, fLastUITapPos) <= DOUBLE_TAP_SLOP) then
    begin
      tapPt := fLastUITapPos;
      fLastUITap := False;
    end
    else
    begin
      fLastUITap := True;
      fLastUITapPos := P;
      fLastUITapTime := GetTickCount64;
    end;
    isAction := IsActionableUI(tapPt);
  end
  else
  begin
    fLastUITap := False;
    isAction := MapToolActive
                or ((gSystem <> nil) and (gSystem.Cursor in [kmcInfo, kmcBeacon, kmcJoinYes, kmcPaintBucket]));
  end;
  SendMove(tapPt);
  prevSelected := nil;
  if gMySpectator <> nil then
    prevSelected := gMySpectator.Selected;
  SendDown(mbLeft, tapPt);
  SendUp(mbLeft, tapPt);

  // Remember taps on text fields: the host shows the soft keyboard only then
  if (ActiveControls <> nil) and (ActiveControls.HitControl(Round(P.X), Round(P.Y)) is TKMSelectableEdit) then
    fTextFieldTapped := True;
  if not isAction and (gMySpectator <> nil) and (gMySpectator.Selected <> prevSelected) then
    isAction := True;

  if isAction then
    HostHaptic;
end;


var
  fLastPeekCtrl: TKMControl;


function RawHintControlAt(const P: TPointF): TKMControl;
var
  ctrls: TKMMasterControl;
  pt: TPoint;
  ctrl: TKMControl;
begin
  Result := nil;
  ctrls := ActiveControls;
  if ctrls = nil then Exit;
  pt := MousePt(P);
  ctrl := ctrls.HitControl(pt.X, pt.Y, True, False);
  while (ctrl <> nil) and (ctrl.Hint = '') and not (ctrl is TKMPanel) do
    ctrl := ctrl.Parent;
  if (ctrl = nil) or (ctrl.Hint = '') then Exit;
  if (ctrl is TKMSelectableEdit)
  or (ctrl is TKMPanel)
  or (ctrl.ClassName = 'TKMTrackBar')
  or (ctrl.ClassName = 'TKMListBox')
  or (ctrl.ClassName = 'TKMColumnBox')
  or ((ctrl.Parent <> nil) and (ctrl.Parent.ClassName = 'TKMScrollBar')) then
    Exit;
  Result := ctrl;
end;


function FindPeekHintControl(const P: TPointF): TKMControl;
begin
  Result := RawHintControlAt(P);
  if Result = nil then Exit;
  if Assigned(Result.OnClickRight) or Assigned(Result.OnClickShift) or Assigned(Result.OnClickHold) then
    Result := nil;
end;


procedure UpdateTouchPeek(const P: TPointF);
var
  ctrl: TKMControl;
begin
  if (gGameApp = nil) or (gGameApp.ActiveInterface = nil) then Exit;
  ctrl := RawHintControlAt(P);
  if ctrl <> fLastPeekCtrl then
  begin
    fLastPeekCtrl := ctrl;
    gGameApp.ActiveInterface.SetTouchHint(ctrl);
    if ctrl <> nil then
      HostHaptic;
  end
  else
  if ctrl <> nil then
    gGameApp.ActiveInterface.SetTouchHint(ctrl);
end;


procedure ClearTouchPeek;
begin
  fLastPeekCtrl := nil;
  if (gGameApp <> nil) and (gGameApp.ActiveInterface <> nil) then
    gGameApp.ActiveInterface.ClearTouchHint;
end;


procedure CheckLongPress;
begin
  if (((fTouchState = tsMapPending) and not GameplayBuildToolActive)
      or (fTouchState = tsUIPending)
      or ((fTouchState = tsSidebarPending) and IsRightClickableUI(fDownPos)))
  and (GetTickCount64 - fDownTime >= LONG_PRESS_MS) then
  begin
    // Right button press with haptic feedback, held until finger is lifted (allows to pick troops direction or hold-repeat +/- 10)
    fLastMapTap := False;
    HostHaptic;
    SendMove(fDownPos);
    SendDown(mbRight, fDownPos);
    if IsOverUI(fDownPos) and (gGameApp <> nil) and (gGameApp.ActiveInterface <> nil) then
      gGameApp.ActiveInterface.SetTouchHint(RawHintControlAt(fDownPos));
    fSecondDownPos := fDownPos;
    fRightHoldMoved := False;
    fTouchState := tsRightHold;
  end
  else
  if (fTouchState in [tsSidebarPending, tsUIDrag])
  and (Dist(fLastPos, fDownPos) <= TAP_SLOP)
  and (GetTickCount64 - fDownTime >= PEEK_HINT_MS)
  and (FindPeekHintControl(fDownPos) <> nil) then
  begin
    // Press & Peek tooltip: cancel pending left-click so lifting finger won't activate the button
    fLastMapTap := False;
    if ActiveControls <> nil then
      ActiveControls.CtrlDown := nil;
    Exclude(fButtons, ssLeft);
    gHostKeyDown[VK_LBUTTON] := False;
    fTouchState := tsHintHold;
    UpdateTouchPeek(fDownPos);
  end;
end;


procedure StartPinch(const P0, P1: TPointF);
var
  vp: TKMViewport;
begin
  fLastMapTap := False;
  vp := GameViewport;
  fPinchStartDist := Max(Dist(P0, P1), 1);
  if vp <> nil then
    fPinchStartZoom := vp.Zoom
  else
    fPinchStartZoom := 1;
  fPinchLastMid := MakePointF((P0.X + P1.X) / 2, (P0.Y + P1.Y) / 2);
  fPinchStartTime := GetTickCount64;
  fPinchMoved := False;
  fTouchState := tsPinch;
end;


procedure kam_touch(aAction, aPointerCount: Integer; X0, Y0, X1, Y1: Single); cdecl;
var
  P0, P1, mid: TPointF;
  prevSelected: TObject;
  modalTap: Boolean;
begin
  if (gGameApp = nil) or (gMain = nil) or not gMain.Started then Exit;
  gHostTouchMode := True;
  IGNORE_MOUSE_SCROLLING := True;
  try
    P0 := MakePointF(X0, Y0);
    P1 := MakePointF(X1, Y1);

    case aAction of
      TOUCH_DOWN:
        begin
          ClearTouchPeek;
          if fLastMapTap
             and (GetTickCount64 - fLastMapTapTime <= DOUBLE_TAP_MS)
             and (Dist(P0, fLastMapTapPos) <= DOUBLE_TAP_SLOP)
             and not IsOverUI(P0)
             and not TouchHouseModeActive
             and not GameplayBuildToolActive
             and not MapToolActive then
          begin
            // Second tap on map: press right mouse button and hold until finger is lifted
            fLastMapTap := False;
            fDownPos := fLastMapTapPos;
            fSecondDownPos := P0;
            fLastPos := fDownPos;
            fDownTime := GetTickCount64;
            fRightHoldMoved := False;
            HostHaptic;
            SendMove(fDownPos);
            SendDown(mbRight, fDownPos);
            fTouchState := tsRightHold;
            Exit;
          end;

          fLastMapTap := False;
          fDownPos := P0;
          fLastPos := P0;
          fDownTime := GetTickCount64;
          StopScrollMomentum;
          SendMove(P0);
          if ScrollableTargetAt(P0) <> nil then
            // Decide on first move: vertical drag scrolls list/memo/panel/sidebar, otherwise tap/drag/long-press
            fTouchState := tsSidebarPending

          else
          if IsOverUI(P0) then
          begin
            if IsRightClickableUI(P0) then
              fTouchState := tsUIPending
            else
            begin
              if IsActionableUI(P0) then
                HostHaptic;
              SendDown(mbLeft, P0);
              fTouchState := tsUIDrag;
            end;
          end
          else
          if TouchHouseModeActive then
          begin
            ActiveGamePlay.UpdateTouchBuildUI;
            if ActiveGamePlay.TouchHousePreviewHit(Round(P0.X), Round(P0.Y)) then
            begin
              ActiveGamePlay.TouchHouseDragStart(Round(P0.X), Round(P0.Y));
              fTouchState := tsHousePreviewPending;
            end
            else
              fTouchState := tsMapPending;
          end
          else
            fTouchState := tsMapPending;
        end;

      TOUCH_POINTER_DOWN:
        if aPointerCount >= 2 then
          case fTouchState of
            tsMapPending, tsMapPan, tsUIPending, tsSidebarPending, tsHousePreviewPending, tsHousePreviewDrag, tsHintHold:
              begin
                ClearTouchPeek;
                StartPinch(P0, P1);
              end;
            tsMapDragLeft, tsUIDrag:
              begin
                // Second finger cancels the left-button drag and starts a two-finger tap or pinch/pan
                SendUp(mbLeft, fLastPos);
                StartPinch(P0, P1);
              end;
          end;

      TOUCH_MOVE:
        case fTouchState of
          tsSidebarPending:
            if Dist(P0, fDownPos) > TAP_SLOP then
            begin
              if Abs(P0.Y - fDownPos.Y) >= Abs(P0.X - fDownPos.X) then
              begin
                fTouchState := tsSidebarScroll;
                fLastUITap := False;
                fScrollTarget := ScrollableTargetAt(fDownPos);
                fScrollInterface := gGameApp.ActiveInterface;
                fScrollAccumPx := 0;
                fScrollVelPxPerSec := 0;
                fLastScrollMoveTime := fDownTime;
                RecordScrollMove(fLastPos.Y - P0.Y);
              end
              else
              begin
                // Horizontal drag (e.g. a slider): press where the finger went down and drag
                if IsActionableUI(fDownPos) then
                  HostHaptic;
                SendDown(mbLeft, fDownPos);
                SendMove(P0);
                fTouchState := tsUIDrag;
              end;
              fLastPos := P0;
            end;
          tsSidebarScroll:
            begin
              RecordScrollMove(fLastPos.Y - P0.Y);
              fLastPos := P0;
            end;

          tsUIPending:
            if Dist(P0, fDownPos) > TAP_SLOP then
            begin
              if IsActionableUI(fDownPos) then
                HostHaptic;
              SendDown(mbLeft, fDownPos);
              SendMove(P0);
              fTouchState := tsUIDrag;
              fLastPos := P0;
            end;
          tsUIDrag, tsMapDragLeft:
            begin
              SendMove(P0);
              fLastPos := P0;
            end;
          tsHousePreviewPending:
            if Dist(P0, fDownPos) > TAP_SLOP then
            begin
              HostHaptic;
              if ActiveGamePlay <> nil then
                ActiveGamePlay.TouchHouseDragMove(Round(P0.X), Round(P0.Y));
              fTouchState := tsHousePreviewDrag;
              fLastPos := P0;
            end;
          tsHousePreviewDrag:
            begin
              if ActiveGamePlay <> nil then
                ActiveGamePlay.TouchHouseDragMove(Round(P0.X), Round(P0.Y));
              fLastPos := P0;
            end;
          tsRightHold:
            begin
              if not fRightHoldMoved and (Dist(P0, fSecondDownPos) > TAP_SLOP) then
                fRightHoldMoved := True;
              if fRightHoldMoved then
              begin
                SendMove(P0);
                fLastPos := P0;
              end;
            end;
          tsHintHold:
            begin
              SendMove(P0);
              UpdateTouchPeek(P0);
              fLastPos := P0;
            end;
          tsMapPending:
            if Dist(P0, fDownPos) > TAP_SLOP then
            begin
              if MapToolActive then
              begin
                HostHaptic;
                SendMove(fDownPos);
                SendDown(mbLeft, fDownPos);
                SendMove(P0);
                fTouchState := tsMapDragLeft;
              end
              else
              begin
                fTouchState := tsMapPan;
                PanViewport(P0.X - fLastPos.X, P0.Y - fLastPos.Y);
              end;
              fLastPos := P0;
            end;
          tsMapPan:
            begin
              PanViewport(P0.X - fLastPos.X, P0.Y - fLastPos.Y);
              fLastPos := P0;
            end;
          tsPinch:
            if aPointerCount >= 2 then
            begin
              mid := MakePointF((P0.X + P1.X) / 2, (P0.Y + P1.Y) / 2);
              if (Abs(Dist(P0, P1) - fPinchStartDist) > PINCH_SLOP) or (Dist(mid, fPinchLastMid) > PINCH_SLOP) then
                fPinchMoved := True;
              if fPinchMoved and (GameViewport <> nil) then
              begin
                ZoomViewport(fPinchStartZoom * Dist(P0, P1) / fPinchStartDist, mid);
                PanViewport(mid.X - fPinchLastMid.X, mid.Y - fPinchLastMid.Y);
                fPinchLastMid := mid;
              end;
            end;
        end;

      TOUCH_POINTER_UP:
        if fTouchState = tsPinch then
        begin
          // Quick two finger tap without movement = right click
          if not fPinchMoved and (GetTickCount64 - fPinchStartTime < TWO_FINGER_TAP_MS) then
          begin
            HostHaptic;
            SendMove(fDownPos);
            SendDown(mbRight, fDownPos);
            SendUp(mbRight, fDownPos);
          end;
          fTouchState := tsConsumed;
        end;

      TOUCH_UP:
        begin
          case fTouchState of
            tsSidebarPending, tsUIPending:
              PerformLeftTap(fDownPos);

            tsSidebarScroll:
              StartScrollMomentum;


            tsMapPending:
              if TouchHouseModeActive then
              begin
                fLastMapTap := False;
                if (ActiveGamePlay <> nil) and ActiveGamePlay.TouchHouseTap(Round(fDownPos.X), Round(fDownPos.Y)) then
                  HostHaptic;
              end
              else
              if GameplayBuildToolActive or MapToolActive then
              begin
                fLastMapTap := False;
                PerformLeftTap(fDownPos);
              end
              else
              begin
                prevSelected := nil;
                if gMySpectator <> nil then
                  prevSelected := gMySpectator.Selected;
                modalTap := (gSystem <> nil) and (gSystem.Cursor in [kmcBeacon, kmcJoinYes]);
                PerformLeftTap(fDownPos);
                // Left-clicking empty ground or enemies is a no-op in KaM Remake, so firing left-click
                // immediately avoids tap latency while still allowing a 2nd map tap to trigger right-click.
                if not modalTap and ((gMySpectator = nil) or (gMySpectator.Selected = prevSelected)) then
                begin
                  fLastMapTap := True;
                  fLastMapTapPos := fDownPos;
                  fLastMapTapTime := GetTickCount64;
                end
                else
                  fLastMapTap := False;
              end;

            tsHousePreviewDrag:
              if ActiveGamePlay <> nil then
                ActiveGamePlay.TouchHouseDragMove(Round(P0.X), Round(P0.Y));

            tsUIDrag, tsMapDragLeft:
              begin
                SendMove(P0);
                SendUp(mbLeft, P0);
                // Remember taps on text fields: the host shows the soft keyboard only then
                if (ActiveControls <> nil) and (ActiveControls.HitControl(Round(P0.X), Round(P0.Y)) is TKMSelectableEdit) then
                  fTextFieldTapped := True;
              end;
            tsRightHold:
              begin
                ClearTouchPeek;
                if fRightHoldMoved then
                begin
                  SendMove(P0);
                  SendUp(mbRight, P0);
                end
                else
                  SendUp(mbRight, fDownPos);
              end;
            tsHintHold:
              ClearTouchPeek;
          end;
          fTouchState := tsIdle;
        end;

      TOUCH_CANCEL:
        begin
          ClearTouchPeek;
          fLastMapTap := False;
          ReleaseButtons(fLastPos);
          fTouchState := tsIdle;
        end;

    end;
  except
    on E: Exception do
      ALog('Exception in touch: ' + E.Message);
  end;
end;


// Physical mouse (USB/Bluetooth) or emulated by the host
procedure kam_mouse(aAction, aButton, X, Y: Integer); cdecl;
var
  btn: TMouseButton;
begin
  if gGameApp = nil then Exit;
  gHostTouchMode := False;
  IGNORE_MOUSE_SCROLLING := False;
  case aButton of
    1: btn := mbRight;
    2: btn := mbMiddle;
    else btn := mbLeft;
  end;
  case aAction of
    TOUCH_DOWN: begin SendMove(MakePointF(X, Y)); SendDown(btn, MakePointF(X, Y)); end;
    TOUCH_MOVE: SendMove(MakePointF(X, Y));
    TOUCH_UP:   SendUp(btn, MakePointF(X, Y));
  end;
end;


procedure kam_wheel(aSteps, X, Y: Integer); cdecl;
var
  handled: Boolean;
begin
  if gGameApp = nil then Exit;
  handled := False;
  gGameApp.MouseWheel(fButtons, aSteps, X, Y, handled);
end;


function ShiftFromFlags(aShift: Integer): TShiftState;
begin
  Result := [];
  if aShift and 1 <> 0 then Include(Result, ssShift);
  if aShift and 2 <> 0 then Include(Result, ssCtrl);
  if aShift and 4 <> 0 then Include(Result, ssAlt);
end;


procedure kam_key(aAction, aVirtualKey, aShift: Integer); cdecl;
var
  key: Word;
begin
  if (gGameApp = nil) or (aVirtualKey < 0) or (aVirtualKey > 255) then Exit;
  key := aVirtualKey;
  case aAction of
    KEY_DOWN:
      begin
        gHostKeyDown[key] := True;
        gGameApp.KeyDown(key, ShiftFromFlags(aShift), True);
      end;
    KEY_UP:
      begin
        gHostKeyDown[key] := False;
        gGameApp.KeyUp(key, ShiftFromFlags(aShift));
      end;
  end;
end;


procedure kam_char(aUnicodeChar: Integer); cdecl;
var
  s: AnsiString;
  I: Integer;
begin
  if gGameApp = nil then Exit;
  {$IFDEF UNICODE}
  gGameApp.KeyPress(Char(aUnicodeChar));
  {$ELSE}
  // Non-unicode build: Char is a byte, feed the UTF-8 sequence
  s := UTF8Encode(UnicodeString(WideChar(aUnicodeChar)));
  for I := 1 to Length(s) do
    gGameApp.KeyPress(s[I]);
  {$ENDIF}
end;


procedure kam_back; cdecl;
begin
  fLastMapTap := False;
  kam_key(KEY_DOWN, VK_ESCAPE, 0);
  kam_key(KEY_UP, VK_ESCAPE, 0);
end;



function kam_text_input_active: Integer; cdecl;
var
  ctrls: TKMMasterControl;
begin
  Result := 0;
  ctrls := ActiveControls;
  if (ctrls <> nil) and (ctrls.CtrlFocus is TKMSelectableEdit) then
    Result := 1;
end;


procedure kam_video_finished; cdecl;
begin
  if gVideoPlayer <> nil then
    gVideoPlayer.HostVideoFinished;
end;


// 1 when a game (map with the gameplay sidebar) is shown, 0 in menus. Menus need the full 1024x720 design size
function kam_in_game: Integer; cdecl;
begin
  Result := 0;
  // Only while the game map with its sidebar is on screen (not the loading page or other menu pages)
  if (gGameApp <> nil) and (gGameApp.Game <> nil) and (gGameApp.ActiveInterface is TKMUserInterfaceGame) then
    Result := 1;
  // Mission results, statistics, and full settings forms need the menu screen size
  if (Result = 1) and (gGame.GamePlayInterface <> nil)
  and (gGameApp.ActiveInterface = gGame.GamePlayInterface)
  and gGame.GamePlayInterface.IsFullScreenPageVisible then
    Result := 0;
  if (Result = 1) and (gGame.MapEditorInterface <> nil)
  and (gGameApp.ActiveInterface = gGame.MapEditorInterface)
  and gGame.MapEditorInterface.IsFullScreenPageVisible then
    Result := 0;
end;


function kam_toolbar_width: Integer; cdecl;
begin
  Result := 0;
  if (kam_in_game = 1) and (GameViewport <> nil) then
    Result := Max(0, GameViewport.ToolbarWidth);
end;



// Bottom edge (render coordinates) of the focused text field, -1 if none. Used to keep it above the soft keyboard
function kam_text_input_bottom: Integer; cdecl;
var
  ctrls: TKMMasterControl;
  sx, sy: Integer;
begin
  Result := -1;
  ctrls := ActiveControls;
  if (ctrls <> nil) and (ctrls.CtrlFocus is TKMSelectableEdit) then
  begin
    sx := ctrls.CtrlFocus.AbsLeft;
    sy := ctrls.CtrlFocus.AbsTop + ctrls.CtrlFocus.Height;
    ctrls.CtrlFocus.LocalToScreen(sx, sy);
    Result := sy;
    // Field in the scrollable sidebar below the screen edge: scroll the sidebar to show it first
    if (Result > gHostScreenHeight) and (gGame <> nil) and (gGameApp.ActiveInterface is TKMUserInterfaceGame) then
    begin
      ScrollSidebarOf(gGameApp.ActiveInterface, Result - gHostScreenHeight + 4);
      sx := ctrls.CtrlFocus.AbsLeft;
      sy := ctrls.CtrlFocus.AbsTop + ctrls.CtrlFocus.Height;
      ctrls.CtrlFocus.LocalToScreen(sx, sy);
      Result := sy;
    end;
  end;
end;



// 1 once after the user tapped a text field
function kam_text_field_tapped: Integer; cdecl;
begin
  Result := Ord(fTextFieldTapped);
  fTextFieldTapped := False;
end;


// Starts the content smoke test (see KM_AndroidSelfTest)
procedure kam_selftest(aMode: PAnsiChar); cdecl;
begin
  if (gMain <> nil) and gMain.Started then
    SelfTestStart(string(aMode));
end;


end.
