unit KromOGLUtils;
{$I KaM_Remake.inc}
// Android implementation of KromOGLUtils (shadows src/utils/KromOGLUtils.pas via -Fu)
interface
uses
  dglOpenGL, LCLType;

type
  TColor4 = Cardinal;
  TTexture = record Tex: Cardinal; U, V: Single; end;

  procedure SetRenderFrame(RenderFrame: HWND; out h_DC: HDC; out h_RC: HGLRC);
  procedure SetRenderFrameAA(DummyFrame, RenderFrame: HWND; AntiAliasing: Byte; out h_DC: HDC; out h_RC: HGLRC);

  procedure BuildFont(h_DC: HDC; FontSize: Integer; FontWeight: Word = FW_NORMAL);
  procedure glPrint(const text: AnsiString);
  procedure glkScale(x: Single);
  procedure glkQuad(Ax, Ay, Bx, By, Cx, Cy, Dx, Dy: Single);
  procedure glkRect(Ax, Ay, Bx, By: Single);
  procedure SetupVSync(aVSync: Boolean);

implementation

procedure SetRenderFrame(RenderFrame: HWND; out h_DC: HDC; out h_RC: HGLRC);
begin
  h_DC := 0;
  h_RC := 0;
  InitOpenGL;
  ReadExtensions;
  ReadImplementationProperties;
end;

procedure SetRenderFrameAA(DummyFrame, RenderFrame: HWND; AntiAliasing: Byte; out h_DC: HDC; out h_RC: HGLRC);
begin
  SetRenderFrame(RenderFrame, h_DC, h_RC);
end;

procedure BuildFont(h_DC: HDC; FontSize: Integer; FontWeight: Word = FW_NORMAL);
begin
  // No system fonts for GL display lists on Android
end;

procedure glPrint(const text: AnsiString);
begin
  if text = '' then Exit;
  glPushAttrib(GL_LIST_BIT);
  glListBase(20000);
  glCallLists(length(text), GL_UNSIGNED_BYTE, PChar(@text[1]));
  glPopAttrib;
end;

procedure glkScale(x: Single);
begin
  glScalef(x, x, x);
end;

procedure glkQuad(Ax, Ay, Bx, By, Cx, Cy, Dx, Dy: Single);
begin
  glvertex2f(Ax, Ay);
  glvertex2f(Bx, By);
  glvertex2f(Cx, Cy);
  glvertex2f(Dx, Dy);
end;

procedure glkRect(Ax, Ay, Bx, By: Single);
begin
  glvertex2f(Ax, Ay);
  glvertex2f(Bx, Ay);
  glvertex2f(Bx, By);
  glvertex2f(Ax, By);
end;

procedure SetupVSync(aVSync: Boolean);
begin
end;

end.
