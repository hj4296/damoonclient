-- 게임 전체 설정. 숫자를 바꾸면 바로 게임 밸런스가 바뀝니다.
local Config = {}

Config.GameName = "블록 아레나"
Config.Version = "1.0.0"

-- 블록 한 칸의 크기(스터드). 캐릭터 키(약 5스터드)와 비교해 마인크래프트 비율에 맞춘 값입니다.
Config.BlockSize = 3

-- 로비와 경기장 위치. 경기장은 동시에 여러 개가 열릴 수 있어서 X축으로 띄워 놓습니다.
Config.LobbyOrigin = Vector3.new(0, 60, 0)
Config.ArenaBase = Vector3.new(2400, 160, 0)
Config.ArenaSpacing = 1200
Config.MaxArenas = 10

Config.DataStoreName = "BlockArena_v1"

Config.Movement = {
	Walk = 16,
	Sprint = 21,
	Sneak = 5,
	JumpHeight = 4.4, -- 1칸은 올라가고 2칸은 못 올라가는 높이
	Gravity = 130,
}

Config.Reach = {
	Attack = 10.5, -- 클라이언트가 공격을 시도하는 거리
	AttackServer = 14, -- 서버가 허용하는 거리(지연 보정 포함)
	Block = 15,
	BlockServer = 20,
}

Config.Combat = {
	HurtTime = 0.45, -- 맞은 뒤 무적 시간
	KnockbackH = 28,
	KnockbackV = 20,
	CritMultiplier = 1.5,
	RegenInterval = 2.5,
	RegenDelay = 4,
	KillCredit = 10, -- 이 시간 안에 때린 사람이 처치 기록을 가져감
	FallSafeBlocks = 3.5,
}

Config.Teams = {
	{ id = "red", name = "빨강", letter = "R", color = Color3.fromRGB(235, 64, 52), hex = "#FF5555" },
	{ id = "blue", name = "파랑", letter = "B", color = Color3.fromRGB(52, 101, 235), hex = "#5577FF" },
	{ id = "green", name = "초록", letter = "G", color = Color3.fromRGB(70, 200, 60), hex = "#55FF55" },
	{ id = "yellow", name = "노랑", letter = "Y", color = Color3.fromRGB(245, 210, 50), hex = "#FFFF55" },
}

Config.Modes = {
	bedwars = {
		id = "bedwars",
		name = "베드워즈",
		icon = "bed",
		color = Color3.fromRGB(235, 64, 52),
		description = "침대를 지키고 상대 침대를 부수세요. 침대가 남아 있으면 다시 살아납니다.",
		minPlayers = 2,
		maxPlayers = 8,
		teamCount = 4,
		teamSize = 2,
	},
	skywars = {
		id = "skywars",
		name = "스카이워즈",
		icon = "sword",
		color = Color3.fromRGB(90, 200, 255),
		description = "하늘섬의 상자에서 장비를 모아 마지막까지 살아남으세요.",
		minPlayers = 2,
		maxPlayers = 8,
	},
	tntrun = {
		id = "tntrun",
		name = "TNT 런",
		icon = "tnt",
		color = Color3.fromRGB(250, 170, 40),
		description = "밟은 블록은 곧 사라집니다. 멈추지 말고 끝까지 버티세요.",
		minPlayers = 2,
		maxPlayers = 12,
	},
}
Config.ModeOrder = { "bedwars", "skywars", "tntrun" }

Config.Waiting = {
	Countdown = 30, -- 최소 인원이 모이면 시작하는 카운트다운
	FullCountdown = 10, -- 꽉 차면 이 값으로 줄어듦
	VoteLock = 4, -- 남은 시간이 이보다 작아지면 투표 마감
	EndDelay = 8, -- 경기가 끝나고 로비로 돌아가기까지
}

-- 스튜디오에서 혼자 테스트할 수 있도록 최소 인원을 1명으로 낮춥니다.
Config.StudioMinPlayers = 1

Config.BedWars = {
	RespawnTime = 5,
	BuildHeight = 18,
	BuildDepth = -12,
	BuildRadius = 70,
	IronInterval = 1.2,
	IronCap = 48,
	GoldInterval = 7,
	GoldCap = 12,
	DiamondIntervals = { 30, 22, 15 },
	DiamondCap = 4,
	EmeraldIntervals = { 60, 45, 30 },
	EmeraldCap = 2,
	Events = {
		{ time = 300, name = "다이아몬드 II", kind = "diamond", tier = 2 },
		{ time = 600, name = "에메랄드 II", kind = "emerald", tier = 2 },
		{ time = 900, name = "다이아몬드 III", kind = "diamond", tier = 3 },
		{ time = 1200, name = "에메랄드 III", kind = "emerald", tier = 3 },
		{ time = 1500, name = "침대 파괴", kind = "beds" },
		{ time = 2100, name = "게임 종료", kind = "end" },
	},
}

Config.SkyWars = {
	CageTime = 10,
	Refills = { 180, 360 },
	TimeLimit = 600,
}

Config.TntRun = {
	StartDelay = 5,
	RemoveDelay = 0.35,
	DoubleJumps = 3,
	DoubleJumpPower = 42,
	TimeLimit = 600,
}

Config.Sounds = {
	Hit = "rbxasset://sounds/swordslash.wav",
	Click = "rbxasset://sounds/electronicpingshort.wav",
	Tick = "rbxasset://sounds/clickfast.wav",
}

return Config
