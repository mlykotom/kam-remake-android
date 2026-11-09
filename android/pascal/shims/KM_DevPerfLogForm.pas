unit KM_DevPerfLogForm;
{$I KaM_Remake.inc}
// Android stub for KM_DevPerfLogForm (shadows src/perflog/KM_DevPerfLogForm.pas via -Fu)
interface
uses
  Forms, KM_CommonTypes, KM_DevPerfLog;

type
  TFormPerfLogs = class(TForm)
  private
    fOnFormChanged: TKMEvent;
    procedure SetOnFormChanged(const aValue: TKMEvent);
  public
    property OnFormChanged: TKMEvent read fOnFormChanged write SetOnFormChanged;
    procedure Show(aPerfLogs: TKMPerfLogs); reintroduce;
    function FormHeight: Integer;
  end;

implementation

procedure TFormPerfLogs.SetOnFormChanged(const aValue: TKMEvent);
begin
  if Self <> nil then
    fOnFormChanged := aValue;
end;

procedure TFormPerfLogs.Show(aPerfLogs: TKMPerfLogs);
begin
end;

function TFormPerfLogs.FormHeight: Integer;
begin
  Result := 0;
end;

end.
