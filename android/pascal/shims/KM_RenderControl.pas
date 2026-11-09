unit KM_RenderControl;
{$I KaM_Remake.inc}
// Android implementation of KM_RenderControl (shadows src/render/KM_RenderControl.pas via -Fu).
// GL context and surface are owned by the Android GLSurfaceView.
interface
uses
  Classes, Types, Controls, KMAndroidHost, KromOGLUtils, dglOpenGL;

type
  TKMResizeEvent = procedure(aWidth, aHeight: Integer) of object;

  TKMRenderControl = class(TWinControl)
  public
    OnRender: TNotifyEvent;
    OnResize: TKMResizeEvent;
    procedure CreateRenderContext;
    procedure DestroyRenderContext;
    procedure SwapBuffers;
    procedure SetSize(aWidth, aHeight: Integer);
  end;

implementation

{ TKMRenderControl }

procedure TKMRenderControl.SwapBuffers;
begin
  // Buffers are swapped by GLSurfaceView after each frame
end;

procedure TKMRenderControl.SetSize(aWidth, aHeight: Integer);
begin
  Width := aWidth;
  Height := aHeight;
  if Assigned(OnResize) then
    OnResize(Width, Height);
end;

procedure TKMRenderControl.CreateRenderContext;
begin
  // Context is already current on the GL thread, gl4es provides desktop GL entry points
  InitOpenGL;
  ReadExtensions;
  ReadImplementationProperties;
  if Assigned(gOnGLInitialized) then
    gOnGLInitialized;
end;

procedure TKMRenderControl.DestroyRenderContext;
begin
end;

end.
