-- 꾸밈 효과: 생성기 블록 회전, 효과음
local CollectionService = game:GetService("CollectionService")
local ReplicatedStorage = game:GetService("ReplicatedStorage")
local RunService = game:GetService("RunService")
local SoundService = game:GetService("SoundService")

local Shared = ReplicatedStorage:WaitForChild("Shared")
local Config = require(Shared:WaitForChild("Config"))
local Net = require(script.Parent.Net)

local Effects = {}
Effects.enabled = true

-- 효과음 이름 -> (소리, 속도)
local SOUNDS: { [string]: { string | number } } = {
	tick = { Config.Sounds.Tick, 1 },
	start = { Config.Sounds.Click, 1.2 },
	buy = { Config.Sounds.Click, 1.5 },
	deny = { Config.Sounds.Tick, 0.6 },
	pickup = { Config.Sounds.Click, 2 },
	bed = { Config.Sounds.Hit, 0.5 },
	eat = { Config.Sounds.Tick, 0.8 },
	equip = { Config.Sounds.Click, 0.9 },
	arrowHit = { Config.Sounds.Click, 1.8 },
}

local pool: { [string]: Sound } = {}

function Effects.play(name: string)
	local entry = SOUNDS[name]
	if not entry or not Effects.enabled then
		return
	end
	local sound = pool[name]
	if not sound then
		local s = Instance.new("Sound")
		s.Name = "UI_" .. name
		s.SoundId = entry[1] :: string
		s.PlaybackSpeed = entry[2] :: number
		s.Volume = 0.45
		s.Parent = SoundService
		pool[name] = s
		sound = s
	end
	SoundService:PlayLocalSound(sound)
end

function Effects.init()
	Net.on("sound", Effects.play)
	local angle = 0
	RunService.RenderStepped:Connect(function(dt)
		angle += dt * 1.6
		for _, part in CollectionService:GetTagged("Spin") do
			if part:IsA("BasePart") then
				local base = part:GetAttribute("SpinBase")
				if typeof(base) ~= "Vector3" then
					base = part.Position
					part:SetAttribute("SpinBase", base)
				end
				local bob = math.sin(angle * 1.5) * 0.25
				part.CFrame = CFrame.new((base :: Vector3) + Vector3.new(0, bob, 0)) * CFrame.Angles(0, angle, 0) * CFrame.Angles(math.rad(30), 0, math.rad(30))
			end
		end
	end)
end

return Effects
