export const DEFAULT_SETTINGS = {
  enabled:true,pathId:'lingkaran',customRaw:[],smoothLevel:4,lissajousFreqX:3,lissajousFreqZ:2,lissajousPhase:90,
  movement:'loop',clockwise:true,intervalMs:3000,rotationSeconds:10,orbitSpeedHz:0.1,width:1,room:1,offsetX:0,offsetZ:0,transitionSeconds:0.8,
  eq:[0,0,0,0,0,0,0,0,0,0],preampDb:0,bassDb:0,subBassPunch:0,vocalExciter:0,highpassHz:20,lowpassHz:20000,channelBalance:0,
  softLimiter:true,vocalIsolatorMode:0,drive:0,compressor:0,roomPresetId:'custom',customIrUri:null,customIrName:null,
  reverbMix:0.25,reverbDecay:2.5,echoMs:350,echoFeedback:0.3,echoMix:0,tempo:1,keepPitch:true,pitchSemitones:0,volume:0.9
};

export const PATHS = [
  {id:'lingkaran',label:'360° Circle',hint:'Mengelilingi kepala secara penuh 360 derajat'},
  {id:'angka-8',label:'Figure-8',hint:'Pola angka delapan depan dan belakang'},
  {id:'pendulum',label:'Pendulum',hint:'Ayunan busur kiri ke kanan'},
  {id:'spiral',label:'Spiral',hint:'Lingkaran mendekat lalu menjauh'},
  {id:'kiri-kanan',label:'Kiri ke Kanan',hint:'Bergerak dari telinga kiri ke kanan'},
  {id:'depan-belakang',label:'Depan ke Belakang',hint:'Bergerak dari depan ke belakang'},
  {id:'diagonal',label:'Diagonal',hint:'Lintasan silang diagonal'},
  {id:'elips',label:'Elips Lebar',hint:'Orbit oval dengan fokus kiri-kanan'},
  {id:'bintang',label:'Bintang',hint:'Lima sudut mengitari pendengar'},
  {id:'lissajous',label:'Lissajous',hint:'Kurva dua frekuensi yang dapat diatur'}
];
