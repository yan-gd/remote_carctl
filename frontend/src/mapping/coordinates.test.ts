import {describe,it,expect} from 'vitest';
import {pixelToWorld,worldToPixel,screenToPixel,freePixel} from './coordinates';
import type {MapGrid} from '../api/types';
const grid:MapGrid={session:'s',mapId:'m',width:100,height:80,resolution:0.05,originX:-2,originY:3,originYaw:Math.PI/2,cells:''};
describe('map coordinate conversion',()=>{
  it('rotates and translates ROS origin and flips screen Y',()=>{
    const p=pixelToWorld(grid,{x:20,y:40});
    expect(p.x).toBeCloseTo(-4);expect(p.y).toBeCloseTo(4);
    const inverse=worldToPixel(grid,p);expect(inverse.x).toBeCloseTo(20);expect(inverse.y).toBeCloseTo(40);
  });
  it('accounts for letterboxing, pan and zoom without reinterpreting margins',()=>{
    const p=screenToPixel({left:10,top:20,width:800,height:400},{x:25,y:20,width:50,height:40},{x:410,y:220});
    expect(p).toEqual({x:50,y:40});
  });
  it('rejects unknown, occupied and outside cells, including top and bottom edges',()=>{
    const small={...grid,width:2,height:2};const data=String.fromCharCode(1,0,2,1);
    expect(freePixel(small,data,{x:0.5,y:1.5})).toBe(true);
    expect(freePixel(small,data,{x:1.5,y:1.5})).toBe(false);
    expect(freePixel(small,data,{x:0.5,y:0.5})).toBe(false);
    expect(freePixel(small,data,{x:2,y:1})).toBe(false);
    expect(freePixel(small,data,{x:1,y:0})).toBe(false);
  });
});
