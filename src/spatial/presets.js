import { DEFAULT_SETTINGS } from './defaults.js';

export const EQ_PRESETS = [
  {label:'Datar',gains:[0,0,0,0,0,0,0,0,0,0]},
  {label:'Bass',gains:[9,8,6,3,1,0,0,0,0,0]},
  {label:'Elektro',gains:[7,6,2,0,-2,-1,2,4,6,6]},
  {label:'Rock',gains:[6,5,2,-1,-2,0,3,4,5,5]},
  {label:'Pop',gains:[-1,2,4,5,3,0,-1,-1,2,3]},
  {label:'Vokal',gains:[-3,-2,-1,0,3,5,5,3,1,-1]},
  {label:'Jazz',gains:[3,2,1,2,-2,-2,0,2,3,4]},
  {label:'Cerah',gains:[-4,-3,-2,-1,0,2,4,6,7,7]},
  {label:'Klasik',gains:[4,3,2,1,0,0,-1,-2,-2,-3]}
];

export const ROOM_PRESETS = [
  {id:'custom',label:'Standar',decay:2.5,mix:0.25,dampingHz:18000},
  {id:'studio',label:'Studio',decay:0.9,mix:0.16,dampingHz:14000},
  {id:'car',label:'Car Audio',decay:0.6,mix:0.22,dampingHz:9500},
  {id:'hall',label:'Concert Hall',decay:3.4,mix:0.36,dampingHz:12000},
  {id:'field',label:'Sound Lapangan',decay:1.8,mix:0.28,dampingHz:16000},
  {id:'cathedral',label:'Cathedral',decay:6.2,mix:0.48,dampingHz:8000}
];

export const BUILTIN_PRESETS = [
  {id:'klasik',name:'Klasik 8D',description:'Lingkaran pelan dengan ruang gema lembut',settings:{pathId:'lingkaran',rotationSeconds:10,orbitSpeedHz:.1,reverbMix:.25,reverbDecay:2.5}},
  {id:'bolak-balik',name:'Bolak-balik Kiri Kanan',description:'Arah berubah setiap 2 detik di jalur kiri ke kanan',settings:{pathId:'kiri-kanan',movement:'pingpong',intervalMs:2000,rotationSeconds:6,orbitSpeedHz:.167,reverbMix:.15}},
  {id:'angka-8',name:'Angka 8 Melayang',description:'Jalur angka 8 dengan gema panjang dan bass hangat',settings:{pathId:'angka-8',rotationSeconds:14,orbitSpeedHz:.071,reverbMix:.4,reverbDecay:4.5,roomPresetId:'hall',bassDb:6,subBassPunch:.35,eq:[5,4,2,0,0,0,0,1,2,2]}},
  {id:'slowed-reverb',name:'Slowed + Reverb',description:'Tempo 0.85x, nada hangat, dan gema Cathedral luas',settings:{pathId:'lingkaran',rotationSeconds:16,orbitSpeedHz:.0625,tempo:.85,keepPitch:false,roomPresetId:'cathedral',reverbMix:.48,reverbDecay:6,bassDb:4,lowpassHz:12000}},
  {id:'nightcore',name:'Nightcore Boost',description:'Tempo cepat 1.25x dengan vokal jernih dan bass punch',settings:{pathId:'spiral',rotationSeconds:6,orbitSpeedHz:.167,tempo:1.25,keepPitch:false,vocalExciter:.4,subBassPunch:.4,reverbMix:.15,eq:[4,3,1,0,1,2,3,4,5,4]}},
  {id:'lorong',name:'Lorong Gema',description:'Depan ke belakang dengan echo dan ruang luas',settings:{pathId:'depan-belakang',movement:'pingpong',intervalMs:4000,rotationSeconds:8,reverbMix:.5,reverbDecay:6,echoMs:420,echoFeedback:.45,echoMix:.3}},
  {id:'malam',name:'Malam Hangat',description:'Elips pelan, bass tebal, nada lembut untuk headset',settings:{pathId:'elips',rotationSeconds:18,bassDb:7,subBassPunch:.4,lowpassHz:9000,eq:[5,4,2,1,0,-1,-1,-2,-3,-4],reverbMix:.35,reverbDecay:3.5,compressor:.3}},
  {id:'speaker-boost',name:'Speaker Clarity',description:'Vokal tegas dan output lebih terkendali untuk speaker HP',settings:{pathId:'pendulum',width:.4,preampDb:4,vocalExciter:.55,reverbMix:.08,compressor:.45,softLimiter:true,eq:[-2,0,2,3,3,4,4,3,2,1]}}
];

export function makePreset(preset) { return {...DEFAULT_SETTINGS,...preset.settings,eq:[...(preset.settings?.eq ?? DEFAULT_SETTINGS.eq)]}; }
