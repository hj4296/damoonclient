-- 게임패스 (VIP, 커스텀 스킨): 가지고 있는지 확인, 구매 창 띄우기, 산 뒤 바로 지급
-- 패스 ID 는 Config.Passes 에 적습니다. 스튜디오에서 ID 가 0 이면 시험용으로 바로 지급합니다.
local MarketplaceService = game:GetService("MarketplaceService")
local ReplicatedStorage = game:GetService("ReplicatedStorage")
local RunService = game:GetService("RunService")

local Config = require(ReplicatedStorage.Shared.Config)
local Session = require(script.Parent.Session)
local Net = require(script.Parent.Net)

local Monetization = {}

-- 지급되었을 때 할 일 (Main 에서 채움)
Monetization.onGranted = function(_player: Player, _key: string) end

local prices: { [string]: number } = {}

local function attributeName(key: string): string
	return "Pass_" .. key
end

function Monetization.owns(player: Player, key: string): boolean
	local state = Session.get(player)
	return state ~= nil and state.passes[key] == true
end

-- 클라이언트 상점 화면에 보낼 정보
function Monetization.info(player: Player)
	local out = {}
	for _, key in Config.PassOrder do
		local pass = Config.Passes[key]
		local studioTest = RunService:IsStudio() and Config.StudioTestPurchases and pass.id == 0
		out[key] = {
			owned = Monetization.owns(player, key),
			price = prices[key] or pass.price,
			available = pass.id ~= 0 or studioTest,
			studioTest = studioTest,
		}
	end
	return out
end

function Monetization.send(player: Player)
	Net.send(player, "passes", Monetization.info(player))
end

function Monetization.grant(player: Player, key: string)
	local state = Session.get(player)
	if not state or state.passes[key] then
		return
	end
	state.passes[key] = true
	player:SetAttribute(attributeName(key), true)
	if key == "vip" then
		player:SetAttribute("VIP", true)
	end
	Monetization.onGranted(player, key)
	Monetization.send(player)
end

-- 들어올 때 한 번 확인
function Monetization.check(player: Player)
	for key, pass in Config.Passes do
		if pass.id ~= 0 then
			local ok, owned = pcall(function()
				return MarketplaceService:UserOwnsGamePassAsync(player.UserId, pass.id)
			end)
			if ok and owned then
				Monetization.grant(player, key)
			elseif not ok then
				warn("[블록 아레나] 패스 확인 실패:", key, owned)
			end
		end
	end
	Monetization.send(player)
end

function Monetization.prompt(player: Player, key: string)
	local pass = Config.Passes[key]
	if not pass or Monetization.owns(player, key) then
		return
	end
	if pass.id ~= 0 then
		local ok, err = pcall(function()
			MarketplaceService:PromptGamePassPurchase(player, pass.id)
		end)
		if not ok then
			warn("[블록 아레나] 구매 창을 열지 못했습니다:", err)
		end
	elseif RunService:IsStudio() and Config.StudioTestPurchases then
		Monetization.grant(player, key)
		Net.send(player, "chat", string.format('<font color="#FFAA00">(스튜디오 시험) %s을(를) 받은 것으로 처리했어요. 실제 판매는 Config.Passes 에 패스 ID 를 적어야 합니다.</font>', pass.name))
	else
		Net.send(player, "chat", '<font color="#FF5555">아직 판매 준비 중인 상품이에요.</font>')
	end
end

MarketplaceService.PromptGamePassPurchaseFinished:Connect(function(player: Player, passId: number, purchased: boolean)
	if not purchased then
		return
	end
	for key, pass in Config.Passes do
		if pass.id ~= 0 and pass.id == passId then
			Monetization.grant(player, key)
			Net.send(player, "chat", string.format('<font color="#55FF55">%s 구매 완료! 고마워요.</font>', pass.name))
		end
	end
end)

-- 대시보드에 적힌 실제 가격 가져오기
task.spawn(function()
	for key, pass in Config.Passes do
		if pass.id ~= 0 then
			local ok, info = pcall(function()
				return MarketplaceService:GetProductInfoAsync(pass.id, Enum.InfoType.GamePass)
			end)
			if ok and type(info) == "table" and type(info.PriceInRobux) == "number" then
				prices[key] = info.PriceInRobux
			end
		end
	end
end)

return Monetization
