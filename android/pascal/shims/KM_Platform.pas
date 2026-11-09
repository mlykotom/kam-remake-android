unit KM_Platform;
{$I KaM_Remake.inc}
// Android platform defaults and hooks (shadows src/common/KM_Platform.pas via -Fu)
interface

const
  DEFAULT_SIDEBAR_SCALE = 1.25;
  DEFAULT_ALLOW_UNSUP_VERSION_SAVE = True;
  DEFAULT_SHOW_VIDEO_OPTIONS = True;
  PLATFORM_VBO_SUPPORTED = {$IFDEF ANDROID_NOVBO}False{$ELSE}True{$ENDIF};
  NEEDS_VBO_UNBIND_AFTER_DRAW = True;

function PlatformSafeInsetLeft: Integer; inline;
function PlatformSafeInsetRight: Integer; inline;
function PlatformTimeGet: Cardinal;
function PlatformTimeGetUsec: Int64;

implementation
uses
  KMAndroidHost;

type
  TKMTimeSpec = record
    tv_sec: Int64;
    tv_nsec: Int64;
  end;

const
  CLOCK_MONOTONIC = 1;

function clock_gettime(aClockId: Integer; aTimeSpec: Pointer): Integer; cdecl; external 'c' name 'clock_gettime';


// Monotonic time in microseconds (not affected by wall-clock changes)
function MonotonicUsec: Int64;
var
  ts: TKMTimeSpec;
begin
  clock_gettime(CLOCK_MONOTONIC, @ts);
  Result := ts.tv_sec * 1000000 + ts.tv_nsec div 1000;
end;


function PlatformSafeInsetLeft: Integer;
begin
  Result := gHostSafeInsetLeft;
end;


function PlatformSafeInsetRight: Integer;
begin
  Result := gHostSafeInsetRight;
end;


function PlatformTimeGet: Cardinal;
begin
  Result := Cardinal((MonotonicUsec div 1000) mod High(Cardinal));
end;


function PlatformTimeGetUsec: Int64;
begin
  Result := MonotonicUsec;
end;


end.
