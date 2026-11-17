// The dev-server proxy of every serve configuration of the administration app and of the public
// site's dev server: /rest goes to the be/ backend on localhost. NIORD_BE_PORT selects a backend on another port
// (./start-local.sh sets it), so several dev servers can run side by side, each against the
// backend it belongs to.
//
// /wms is the sea-chart layer. It is a separate upstream in the container and has no local
// equivalent, so it is proxied only when NIORD_WMS_URL names one; the public site ships with
// wmsLayer disabled, so the layer is not requested until a country configuration enables it.
const backendPort = process.env['NIORD_BE_PORT'] ?? '8888';
const wmsTarget = process.env['NIORD_WMS_URL'];

export default {
  '/rest': {
    target: `http://localhost:${backendPort}`,
    secure: false,
  },
  ...(wmsTarget ? { '/wms': { target: wmsTarget, secure: false, changeOrigin: true } } : {}),
};
