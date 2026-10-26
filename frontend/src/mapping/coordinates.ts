import type { MapGrid } from '../api/types';
type XY = {x:number;y:number};

/** ROS cells start at the bottom left; browser pixels start at the top left. */
export function pixelToWorld(grid:MapGrid, point:XY):XY {
  const x=point.x*grid.resolution, y=(grid.height-point.y)*grid.resolution;
  const c=Math.cos(grid.originYaw),s=Math.sin(grid.originYaw);
  return {x:grid.originX+c*x-s*y,y:grid.originY+s*x+c*y};
}
export function worldToPixel(grid:MapGrid, point:XY):XY {
  const x=point.x-grid.originX,y=point.y-grid.originY,c=Math.cos(grid.originYaw),s=Math.sin(grid.originYaw);
  return {x:(c*x+s*y)/grid.resolution,y:grid.height-(-s*x+c*y)/grid.resolution};
}
export function freePixel(grid:MapGrid, cells:string, p:XY):boolean {
  const x=Math.floor(p.x),y=Math.floor(grid.height-p.y);
  return x>=0&&x<grid.width&&y>=0&&y<grid.height&&cells.charCodeAt(y*grid.width+x)===1;
}
/** Account for SVG letterboxing, zoom and pan. Outside the image is rejected later. */
export function screenToPixel(rect:{left:number;top:number;width:number;height:number},
  view:{x:number;y:number;width:number;height:number},client:XY):XY {
  const scale=Math.min(rect.width/view.width,rect.height/view.height);
  return {x:view.x+(client.x-rect.left-(rect.width-view.width*scale)/2)/scale,
    y:view.y+(client.y-rect.top-(rect.height-view.height*scale)/2)/scale};
}
