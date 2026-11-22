unit KM_GUIGameBuild;
{$I KaM_Remake.inc}
interface
uses
  StrUtils, SysUtils,
  KM_Controls, KM_ControlsBase,
  KM_InterfaceGame;


type
  TKMGUIGameBuild = class
  private
    procedure SelectButton(aButton: TKMButtonFlat);
    procedure Build_ButtonClick(Sender: TObject);
  protected
    Panel_Build: TKMPanel;
      Label_Build: TKMLabel;
      Image_Build_Selected: TKMImage;
      Image_BuildCost_WoodPic: TKMImage;
      Image_BuildCost_StonePic: TKMImage;
      Label_BuildCost_Wood: TKMLabel;
      Label_BuildCost_Stone: TKMLabel;
      Button_BuildRoad: TKMButtonFlat;
      Button_BuildField: TKMButtonFlat;
      Button_BuildWine: TKMButtonFlat;
      Button_BuildCancel: TKMButtonFlat;
      Button_Build: array [1..GUI_HOUSE_COUNT] of TKMButtonFlat;
  public
    constructor Create(aParent: TKMPanel);
    procedure ClearSelection;
    procedure PlanRoad;
    procedure PlanField;
    procedure PlanWine;
    procedure ErasePlan;
    procedure Show;
    procedure Hide;
    function Visible: Boolean;
    procedure UpdateHotkeys;
    procedure UpdateState;
  end;


implementation
uses
  KM_Defaults, KM_Platform, KM_Utils,
  KM_RenderUI, KM_Cursor, KM_HandsCollection,
  KM_Resource, KM_ResFonts, KM_ResHouses, KM_ResTexts, KM_ResTypes, KM_ResWares;


{ TKMGUIGameBuild }
constructor TKMGUIGameBuild.Create(aParent: TKMPanel);
var
  I: Integer;
begin
  inherited Create;

  Panel_Build := TKMPanel.Create(aParent, TB_PAD, 44, TB_WIDTH, 332);
    Label_Build := TKMLabel.Create(Panel_Build, 0, 10, TB_WIDTH, 0, '', fntOutline, taCenter);
    Image_Build_Selected := TKMImage.Create(Panel_Build, 0, 40, 32, 32, 335);
    Image_Build_Selected.ImageCenter;
    Image_BuildCost_WoodPic := TKMImage.Create(Panel_Build, 67, 40, 32, 32, 353);
    Image_BuildCost_WoodPic.ImageCenter;
    Image_BuildCost_StonePic := TKMImage.Create(Panel_Build, 122, 40, 32, 32, 352);
    Image_BuildCost_StonePic.ImageCenter;
    Label_BuildCost_Wood  := TKMLabel.Create(Panel_Build,  97, 50, 20, 20, '', fntOutline, taLeft);
    Label_BuildCost_Stone := TKMLabel.Create(Panel_Build, 152, 50, 20, 20, '', fntOutline, taLeft);

    Button_BuildRoad    := TKMButtonFlat.Create(Panel_Build,   0, 80, 33, 33, 335);
    Button_BuildField   := TKMButtonFlat.Create(Panel_Build,  37, 80, 33, 33, 337);
    Button_BuildWine    := TKMButtonFlat.Create(Panel_Build,  74, 80, 33, 33, 336);
    Button_BuildCancel  := TKMButtonFlat.Create(Panel_Build, 148, 80, 33, 33, 340);
    Button_BuildRoad.OnClick    := Build_ButtonClick;
    Button_BuildField.OnClick   := Build_ButtonClick;
    Button_BuildWine.OnClick    := Build_ButtonClick;
    Button_BuildCancel.OnClick  := Build_ButtonClick;

    for I := 1 to GUI_HOUSE_COUNT do
    if GUIHouseOrder[I] <> htNone then
    begin
      Button_Build[I] := TKMButtonFlat.Create(Panel_Build, ((I-1) mod 5)*37, 120+((I-1) div 5)*37, 33, 33,
                                              gRes.Houses[GUIHouseOrder[I]].GUIIcon);
      Button_Build[I].Tag := Byte(GUIHouseOrder[I]);
      Button_Build[I].OnClick := Build_ButtonClick;
      Button_Build[I].Hint := gRes.Houses[GUIHouseOrder[I]].HouseName;
    end;
end;


procedure TKMGUIGameBuild.ClearSelection;
var
  I: Integer;
begin
  for I := 0 to Panel_Build.ChildCount - 1 do
    if Panel_Build.Childs[I] is TKMButtonFlat then
      TKMButtonFlat(Panel_Build.Childs[I]).Down := False;

  gCursor.Mode := cmNone;
  gCursor.Tag1 := 0;
  Label_BuildCost_Wood.Caption  := '-';
  Label_BuildCost_Stone.Caption := '-';
  Label_Build.Caption := '';
  Image_Build_Selected.TexID := 0;
end;


procedure TKMGUIGameBuild.PlanRoad;
begin
  SelectButton(Button_BuildRoad);
end;


procedure TKMGUIGameBuild.PlanField;
begin
  SelectButton(Button_BuildField);
end;


procedure TKMGUIGameBuild.PlanWine;
begin
  SelectButton(Button_BuildWine);
end;


procedure TKMGUIGameBuild.ErasePlan;
begin
  SelectButton(Button_BuildCancel);
end;


procedure TKMGUIGameBuild.SelectButton(aButton: TKMButtonFlat);

  procedure SetCost(aCursor: TKMCursorMode; aTag, aTexId, aWood, aStone: Word; const aCaption: UnicodeString);
  begin
    gCursor.Mode := aCursor;
    gCursor.Tag1 := aTag;

    Label_BuildCost_Wood.Caption  := IfThen(aWood <> 0, IntToStr(aWood), '-');
    Label_BuildCost_Stone.Caption := IfThen(aStone <> 0, IntToStr(aStone), '-');
    Label_Build.Caption := aCaption;
    Image_Build_Selected.TexID := aTexId;
  end;

var
  I: Integer;
  house: TKMHouseType;
  houseSpec: TKMHouseSpec;
begin
  if aButton = nil then
  begin
    ClearSelection;
    Exit;
  end;

  //Release all buttons (houses and fields)
  for I := 0 to Panel_Build.ChildCount - 1 do
    if Panel_Build.Childs[I] is TKMButtonFlat then
      TKMButtonFlat(Panel_Build.Childs[I]).Down := False;

  //Press the button
  aButton.Down := True;

  if Button_BuildCancel.Down then
    SetCost(cmErase, 0, 340, 0, 0, gResTexts[TX_BUILD_DEMOLISH])
  else
  if Button_BuildRoad.Down then
    SetCost(cmRoad, 0, 335, 0, 1, gResTexts[TX_BUILD_ROAD])
  else
  if Button_BuildField.Down then
    SetCost(cmField, 0, 337, 0, 0, gResTexts[TX_BUILD_FIELD])
  else
  if Button_BuildWine.Down then
    SetCost(cmWine, 0, 336, 1, 0, gResTexts[TX_BUILD_WINE])
  else
  begin
    house := TKMHouseType(aButton.Tag);
    houseSpec := gRes.Houses[house];
    SetCost(cmHouses, Byte(house), houseSpec.GUIIcon, houseSpec.WoodCost, houseSpec.StoneCost, houseSpec.HouseName);
  end;
end;


procedure TKMGUIGameBuild.Build_ButtonClick(Sender: TObject);
begin
  if (Sender = nil) or not (Sender is TKMButtonFlat)
     or (PlatformIsTouchMode and TKMButtonFlat(Sender).Down) then
    ClearSelection
  else
    SelectButton(TKMButtonFlat(Sender));
end;


function FormatBuildCostHint(aWood, aStone: Word): UnicodeString;
begin
  if (aWood > 0) and (aStone > 0) then
    Result := Format('%d %s, %d %s', [aWood, gRes.Wares[wtTimber].Title, aStone, gRes.Wares[wtStone].Title])
  else
  if aWood > 0 then
    Result := Format('%d %s', [aWood, gRes.Wares[wtTimber].Title])
  else
  if aStone > 0 then
    Result := Format('%d %s', [aStone, gRes.Wares[wtStone].Title])
  else
    Result := '';
end;


procedure TKMGUIGameBuild.Show;
begin
  Panel_Build.Show;
  UpdateHotkeys;
  UpdateState;
  if PlatformIsTouchMode then
    ClearSelection
  else
    SelectButton(Button_BuildRoad);
end;


procedure TKMGUIGameBuild.Hide;
begin
  //Reset cursor
  ClearSelection;
  Panel_Build.Hide;
end;


function TKMGUIGameBuild.Visible: Boolean;
begin
  Result := Panel_Build.Visible;
end;


procedure TKMGUIGameBuild.UpdateHotkeys;
begin
  Button_BuildRoad.Hint   := GetHintWHotkey(TX_BUILD_ROAD_HINT,   kfPlanRoad);
  Button_BuildField.Hint  := GetHintWHotkey(TX_BUILD_FIELD_HINT,  kfPlanField);
  Button_BuildWine.Hint   := GetHintWHotkey(TX_BUILD_WINE_HINT,   kfPlanWine);
  Button_BuildCancel.Hint := GetHintWHotkey(TX_BUILD_CANCEL_HINT, kfErasePlan);
  if PlatformIsTouchMode then
  begin
    Button_BuildRoad.Hint := Button_BuildRoad.Hint + '|' + FormatBuildCostHint(0, 1);
    Button_BuildWine.Hint := Button_BuildWine.Hint + '|' + FormatBuildCostHint(1, 0);
  end;
end;


procedure TKMGUIGameBuild.UpdateState;
var
  I: Integer;
  ht: TKMHouseType;
  spec: TKMHouseSpec;
  costStr: UnicodeString;
begin
  for I := 1 to GUI_HOUSE_COUNT do
  begin
    ht := GUIHouseOrder[I];
    if ht = htNone then Continue;
    spec := gRes.Houses[ht];
    if gMySpectator.Hand.Locks.HouseCanBuild(ht) then
    begin
      Button_Build[I].Enable;
      Button_Build[I].TexID := spec.GUIIcon;
      Button_Build[I].OnClick := Build_ButtonClick;
      if PlatformIsTouchMode then
      begin
        Button_Build[I].Hint := spec.HouseName;
        costStr := FormatBuildCostHint(spec.WoodCost, spec.StoneCost);
        if costStr <> '' then
          Button_Build[I].Hint := Button_Build[I].Hint + '|' + costStr;
      end
      else
        Button_Build[I].Hint := spec.HouseName + gResTexts[TX_BUILD_HOUSE_POSTFIX];

    end
    else
    begin
      if Button_Build[I].Down then
        ClearSelection;
      Button_Build[I].OnClick := nil;
      Button_Build[I].TexID := 41;
      if PlatformIsTouchMode
      and not gMySpectator.Hand.Locks.HouseBlocked[ht]
      and (spec.ReleasedBy <> htNone) then
        Button_Build[I].Hint := Format('%s|(%s: %s)',
          [spec.HouseName, gResTexts[TX_HOUSE_NOT_AVAILABLE], gRes.Houses[spec.ReleasedBy].HouseName])
      else
        Button_Build[I].Hint := gResTexts[TX_HOUSE_NOT_AVAILABLE]; //Building not available
    end;
  end;
end;



end.
