unit KM_Platform;
{$I KaM_Remake.inc}
// Desktop platform defaults and hooks (shadowed on Android by android/pascal/shims/KM_Platform.pas)
interface

const
  DEFAULT_SIDEBAR_SCALE = 1.0;
  DEFAULT_ALLOW_UNSUP_VERSION_SAVE = {$IFDEF DEBUG}True{$ELSE}False{$ENDIF};
  DEFAULT_SHOW_VIDEO_OPTIONS = {$IFDEF VIDEOS}True{$ELSE}False{$ENDIF};
  HAS_DESKTOP_RESOLUTION_OPTIONS = True;
  HAS_PLATFORM_UI_SCALE = False;
  PLATFORM_VBO_SUPPORTED = True;
  NEEDS_VBO_UNBIND_AFTER_DRAW = False;

function PlatformSafeInsetLeft: Integer; inline;
function PlatformSafeInsetRight: Integer; inline;
function PlatformIsTouchMode: Boolean; inline;
function PlatformGetUIScale: Single;
procedure PlatformSetUIScale(aScale: Single);
function PlatformTimeGet: Cardinal;
function PlatformTimeGetUsec: Int64;

implementation
uses
  {$IFDEF MSWindows} Windows, MMSystem, {$ENDIF}
  SysUtils;


function PlatformSafeInsetLeft: Integer;
begin
  Result := 0;
end;


function PlatformSafeInsetRight: Integer;
begin
  Result := 0;
end;


function PlatformIsTouchMode: Boolean;
begin
  Result := False;
end;


function PlatformGetUIScale: Single;
begin
  Result := 1.0;
end;


procedure PlatformSetUIScale(aScale: Single);
begin
  // Desktop window/fullscreen size is controlled by resolution settings
end;



function PlatformTimeGet: Cardinal;
begin
  {$IFDEF MSWindows}
  Result := TimeGetTime; //Return milliseconds with ~1ms precision
  {$ENDIF}
  {$IFDEF Unix}
  Result := Cardinal(Trunc(Now * 24 * 60 * 60 * 1000) mod High(Cardinal));
  {$ENDIF}
end;


function PlatformTimeGetUsec: Int64;
{$IFDEF WDC}
var
  freq, count: Int64;
  factor: Double;
{$ENDIF}
begin
  {$IFDEF FPC}
  //Stub for now
  Result := Int64(Trunc(Now * 24 * 60 * 60 * 1000000) mod High(Int64));
  {$ENDIF}
  {$IFDEF WDC}
  QueryPerformanceFrequency(freq);
  QueryPerformanceCounter(count);
  factor := 1000000 / freq;
  Result := Round( count * factor );
  {$ENDIF}
end;


end.
