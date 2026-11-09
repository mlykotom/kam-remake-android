unit KM_Video;
{$I KaM_Remake.inc}
// Android implementation of KM_Video (shadows src/media/KM_Video.pas via -Fu).
// Videos are played by the Android host (libVLC in Java); this unit keeps the queue and game-side logic.
interface
uses
  SysUtils, Types, Classes, Generics.Collections, Controls;

type
  TKMVideoPlayerCallback = procedure of object;

  TKMVideoFileKind = (
    vfkNone,
    vfkStarting //Game starting video
  );

  TKMVideoFile = record
    Path: string;
    Kind: TKMVideoFileKind;
  end;

  TKMVideoPlayer = class
  private
    fPlayerEnabled: Boolean;
    fIndex: Integer;
    fCallback: TKMVideoPlayerCallback;
    fBrightness: Integer;
    fVideoList: TList<TKMVideoFile>;
    fHostPlaying: Boolean;

    function GetPlayerEnabled: Boolean;
    function TryGetPathFile(const aPathRelative: string; var aFileName: string): Boolean;
    procedure AddVideoToList(aPath: string; aKind: TKMVideoFileKind = vfkNone);
  public
    constructor Create(aPlayerEnabled: Boolean);
    destructor Destroy; override;

    property PlayerEnabled: Boolean read GetPlayerEnabled;

    procedure AddCampaignVideo(const aCampaignPath, aVideoName: string);
    procedure AddMissionVideo(const aMissionFile, aVideoName: string);
    procedure AddVideo(const AVideoName: String; aKind: TKMVideoFileKind = vfkNone);

    procedure Play;
    procedure Stop;
    procedure Pause;
    procedure Resume;
    procedure SetCallback(aCallback: TKMVideoPlayerCallback);

    procedure Resize(aWidth, aHeight: Integer);
    procedure UpdateState;
    procedure Paint;

    procedure KeyDown(Key: Word; Shift: TShiftState);
    procedure MouseDown(Button: TMouseButton; Shift: TShiftState; X,Y: Integer);

    function IsActive: Boolean;
    function IsPlay: Boolean;
    procedure HostVideoFinished; // Called when the host player finished or was skipped
  end;

var
  gVideoPlayer: TKMVideoPlayer;

implementation
uses
  KM_ResLocales, KM_GameApp, KM_GameSettings, KM_Music, KM_Sound,
  KMAndroidHost, KM_Defaults;

const
  VIDEOFILE_PATH    = 'data' + PathDelim + 'gfx' + PathDelim + 'video' + PathDelim;
  FADE_MUSIC_TIME   = 500;  // Music fade time, in ms
  UNFADE_MUSIC_TIME = 2000; // Music unfade time, in ms


{ TKMVideoPlayer }
constructor TKMVideoPlayer.Create(aPlayerEnabled: Boolean);
begin
  inherited Create;

  fPlayerEnabled := aPlayerEnabled;
  if not fPlayerEnabled then Exit;

  fIndex := 0;
  fCallback := nil;
  fVideoList := TList<TKMVideoFile>.Create;
end;


destructor TKMVideoPlayer.Destroy;
begin
  if fPlayerEnabled then
    fVideoList.Free;

  inherited;
end;


function TKMVideoPlayer.GetPlayerEnabled: Boolean;
begin
  if Self = nil then Exit(False);
  Result := fPlayerEnabled;
end;


procedure TKMVideoPlayer.AddVideoToList(aPath: string; aKind: TKMVideoFileKind = vfkNone);
var
  videoFileData: TKMVideoFile;
begin
  if Self = nil then Exit;
  if not fPlayerEnabled then Exit;

  videoFileData.Path := aPath;
  videoFileData.Kind := aKind;
  fVideoList.Add(videoFileData);
end;


procedure TKMVideoPlayer.AddCampaignVideo(const aCampaignPath, aVideoName: string);
var
  path: string;
begin
  if Self = nil then Exit;
  if not fPlayerEnabled then Exit;
  if not gGameSettings.Video.Enabled then Exit;

  if TryGetPathFile(ExtractRelativePath(ExeDir, aCampaignPath) + aVideoName, path)
  or TryGetPathFile(VIDEOFILE_PATH + aVideoName, path) then
    AddVideoToList(path);
end;


procedure TKMVideoPlayer.AddMissionVideo(const aMissionFile, aVideoName: string);
var
  missionPath, fileName, path: string;
begin
  if Self = nil then Exit;
  if not fPlayerEnabled then Exit;
  if not gGameSettings.Video.Enabled then Exit;

  missionPath := ExtractFilePath(aMissionFile);
  fileName := ExtractFileName(ChangeFileExt(aMissionFile, '')) + '.' + aVideoName;

  if TryGetPathFile(missionPath + fileName, path)
  or TryGetPathFile(missionPath + aVideoName, path)
  or TryGetPathFile(VIDEOFILE_PATH + aVideoName, path) then
    AddVideoToList(path);
end;


procedure TKMVideoPlayer.AddVideo(const aVideoName: String; aKind: TKMVideoFileKind = vfkNone);
var
  path: string;
begin
  if Self = nil then Exit;
  if not fPlayerEnabled then Exit;
  if not gGameSettings.Video.Enabled then Exit;

  if TryGetPathFile(aVideoName, path)
  or TryGetPathFile(VIDEOFILE_PATH + aVideoName, path) then
    AddVideoToList(path, aKind);
end;


procedure TKMVideoPlayer.Pause;
begin
end;


procedure TKMVideoPlayer.Resume;
begin
end;


procedure TKMVideoPlayer.SetCallback(aCallback: TKMVideoPlayerCallback);
begin
  if Self = nil then Exit;
  if not fPlayerEnabled then Exit;
  fCallback := aCallback;
end;


procedure TKMVideoPlayer.Resize(aWidth, aHeight: Integer);
begin
end;


procedure TKMVideoPlayer.UpdateState;
begin
end;


procedure TKMVideoPlayer.Paint;
begin
end;


procedure TKMVideoPlayer.KeyDown(Key: Word; Shift: TShiftState);
begin
end;


procedure TKMVideoPlayer.MouseDown(Button: TMouseButton; Shift: TShiftState; X,Y: Integer);
begin
end;


function TKMVideoPlayer.IsActive: Boolean;
begin
  if Self = nil then Exit(False);
  if not fPlayerEnabled then Exit(False);
  Result := fHostPlaying or (fVideoList.Count > 0);
end;


function TKMVideoPlayer.IsPlay: Boolean;
begin
  if Self = nil then Exit(False);
  if not fPlayerEnabled then Exit(False);
  Result := fHostPlaying;
end;


procedure TKMVideoPlayer.Play;
begin
  if Self = nil then Exit;
  if not fPlayerEnabled then Exit;
  if fIndex >= fVideoList.Count then Exit;

  if Assigned(gGameApp) then
  begin
    gSoundPlayer.AbortAllFadeSounds;
    gSoundPlayer.AbortAllScriptSounds;
    gSoundPlayer.AbortAllLongSounds;
    gMusic.StopPlayingOtherFile;
    if fVideoList[fIndex].Kind = vfkStarting then
      gMusic.Fade(0)
    else
      gMusic.Fade(FADE_MUSIC_TIME);
  end;
  fBrightness := gGameSettings.GFX.Brightness;
  fHostPlaying := True;
  if Assigned(gHostPlayVideo) then
    gHostPlayVideo(UnicodeString(fVideoList[fIndex].Path), Round(gGameSettings.Video.VideoVolume * 100), gGameSettings.Video.VideoStretch)
  else
    HostVideoFinished;
end;


procedure TKMVideoPlayer.Stop;
var
  startingVideo: Boolean;
begin
  if Self = nil then Exit;
  if not fPlayerEnabled then Exit;
  if fVideoList.Count = 0 then Exit;

  if fHostPlaying then
  begin
    fHostPlaying := False;
    if Assigned(gHostStopVideo) then
      gHostStopVideo;
  end;

  startingVideo := ( fVideoList[fIndex].Kind = vfkStarting );

  Inc(fIndex);
  if fIndex >= fVideoList.Count then
  begin
    fIndex := 0;
    fVideoList.Clear;

    if Assigned(gGameApp) then
    begin
      if startingVideo then
        gMusic.UnfadeStarting
      else
        gMusic.Unfade(UNFADE_MUSIC_TIME);
    end;

    if Assigned(fCallback) then
    begin
      fCallback;
      fCallback := nil;
    end;
  end
  else
    Play;
end;


procedure TKMVideoPlayer.HostVideoFinished;
begin
  if Self = nil then Exit;
  if not fHostPlaying then Exit;

  fHostPlaying := False;
  Stop;
end;


function TKMVideoPlayer.TryGetPathFile(const aPathRelative: string; var aFileName: string): Boolean;
var
  I: Integer;
  searchRec: TSearchRec;
  fileName, path, f: string;
  localePostfixes: TStringList;
begin
  if Self = nil then Exit(False);
  if not fPlayerEnabled then Exit(False);
  Assert(gResLocales <> nil, 'gResLocales should be already loaded!');

  Result := False;
  aFileName := '';

  path := ExtractFilePath(aPathRelative);
  if not DirectoryExists(ExeDir + path) then
    Exit;

  localePostfixes := TStringList.Create;
  try
    localePostfixes.Add('.' + UnicodeString(gResLocales.UserLocale));
    localePostfixes.Add('.' + UnicodeString(gResLocales.FallbackLocale));
    localePostfixes.Add('.' + UnicodeString(gResLocales.DefaultLocale));
    localePostfixes.Add('');

    fileName := ExtractFileName(aPathRelative);
    for I := 0 to localePostfixes.Count - 1 do
    begin
      if FindFirst(ExeDir + path + '*', faAnyFile, searchRec) = 0 then
      try
        repeat
          if (searchRec.Name = '.') or (searchRec.Name = '..') then
            Continue;

          f := fileName + localePostfixes[I] + ExtractFileExt(searchRec.Name);
          if CompareStr(searchRec.Name, f) = 0 then
          begin
            aFileName := ExeDir + path + searchRec.Name;
            Exit(True);
          end;

        until FindNext(searchRec) <> 0;
      finally
        FindClose(searchRec);
      end;
    end;
  finally
    localePostfixes.Free;
  end;
end;


end.
